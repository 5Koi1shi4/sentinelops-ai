package io.sentinelops.executor.adapter.http;

import io.sentinelops.executor.controlplane.ExecutorOAuth2TokenProvider;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.ExecutionStepResult;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.RunbookAdapter;
import io.sentinelops.executor.runbook.UnsupportedRunbookStepException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class ProductionHttpRunbookAdapter implements RunbookAdapter {
    private static final String VERSION = "production-http-v1";
    private final HttpActionCatalog catalog;
    private final ExecutorOAuth2TokenProvider tokens;
    private final ObjectMapper mapper;
    private final AddressResolver addressResolver;

    public ProductionHttpRunbookAdapter(HttpActionCatalog catalog,
            ExecutorOAuth2TokenProvider tokens, ObjectMapper mapper) {
        this(catalog, tokens, mapper, InetAddress::getAllByName);
    }

    ProductionHttpRunbookAdapter(HttpActionCatalog catalog,
            ExecutorOAuth2TokenProvider tokens, ObjectMapper mapper, AddressResolver addressResolver) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.addressResolver = Objects.requireNonNull(addressResolver, "addressResolver");
    }

    @Override public String adapterId() { return "production-http"; }
    @Override public Set<String> supportedOperations() { return catalog.actions().keySet(); }

    @Override
    public ExecutionStepResult execute(AuthorizedRunbookStep step, IdempotencyContext context) {
        return execute(step, context, () -> {});
    }

    @Override
    public ExecutionStepResult execute(AuthorizedRunbookStep step, IdempotencyContext context,
            Runnable beforeTransport) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(beforeTransport, "beforeTransport");
        var action = catalog.actions().get(step.operation());
        if (!adapterId().equals(step.adapterId()) || action == null
                || !action.targetAlias().equals(step.target())
                || !step.executionId().equals(context.executionId())
                || !step.stepId().equals(context.stepId())
                || step.fencingToken() != context.fencingToken()
                || !step.parameters().keySet().equals(action.parameters().keySet())) {
            throw new UnsupportedRunbookStepException("HTTP Runbook step is not cataloged");
        }
        for (var parameter : action.parameters().entrySet()) {
            if (!parameter.getValue().accepts(step.parameters().get(parameter.getKey()))) {
                throw new UnsupportedRunbookStepException("HTTP Runbook parameter is outside catalog bounds");
            }
        }
        InetAddress[] checkedAddresses = resolveAndValidate(action);
        var body = new LinkedHashMap<String, Object>();
        action.bodyTemplate().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Object template = entry.getValue();
            body.put(entry.getKey(), template instanceof String text && text.startsWith("${")
                    ? step.parameters().get(text.substring(2, text.length() - 1)) : template);
        });
        final String bodyJson;
        try {
            bodyJson = mapper.writeValueAsString(body);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Cataloged HTTP body cannot be serialized", failure);
        }
        String accessToken = tokens.accessToken(action.audience(), action.scope());
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("HTTP target credential is unavailable");
        }
        var request = new HttpUriRequestBase(action.method(), action.uri());
        request.setHeader("Authorization", "Bearer " + accessToken);
        request.setHeader("Idempotency-Key", context.key());
        request.setHeader("X-SentinelOps-Fencing-Token", Long.toString(context.fencingToken()));
        request.setEntity(new StringEntity(bodyJson, ContentType.APPLICATION_JSON));
        String requestHash = hash(VERSION + '|' + step.operation() + '|' + action.uri() + '|' + bodyJson);
        var connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(2))
                .setSocketTimeout(Timeout.ofSeconds(5))
                .build();
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinnedResolver(action.uri().getHost(), checkedAddresses))
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        var requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                .setResponseTimeout(Timeout.ofSeconds(5))
                .build();
        try (var client = HttpClients.custom()
                .setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build()) {
            beforeTransport.run();
            int status;
            try (var response = client.execute(request)) {
                status = response.getCode();
            }
            if (status >= 200 && status < 300) {
                return ExecutionStepResult.succeeded(VERSION, requestHash, Map.of("httpStatus", status));
            }
            if (status >= 300 && status < 400) {
                throw new HttpTargetException("HTTP target redirected after dispatch; outcome is unknown");
            }
            if (status == 429 || status >= 500) {
                throw new HttpTargetException("HTTP target outcome requires reconciliation");
            }
            return ExecutionStepResult.failed(VERSION, requestHash,
                    Map.of("errorCode", "target_rejected", "httpStatus", status));
        } catch (InterruptedIOException failure) {
            Thread.currentThread().interrupt();
            throw new HttpTargetException("HTTP target request interrupted", failure);
        } catch (IOException failure) {
            throw new HttpTargetException("HTTP target outcome is unknown", failure);
        }
    }

    private InetAddress[] resolveAndValidate(HttpActionCatalog.Action action) {
        try {
            InetAddress[] addresses = addressResolver.resolve(action.uri().getHost());
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException("HTTP action has no resolved addresses");
            }
            for (InetAddress address : addresses) {
                if (address == null || (!action.allowPrivateNetwork() && isPrivateAddress(address))) {
                    throw new UnsupportedRunbookStepException("HTTP action resolved to a private address");
                }
            }
            return addresses.clone();
        } catch (IOException failure) {
            throw new HttpTargetException("HTTP action hostname could not be resolved", failure);
        }
    }

    private DnsResolver pinnedResolver(String approvedHost, InetAddress[] addresses) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!approvedHost.equalsIgnoreCase(host)) {
                    throw new UnknownHostException("Host is outside the HTTP action catalog");
                }
                return addresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                if (!approvedHost.equalsIgnoreCase(host)) {
                    throw new UnknownHostException("Host is outside the HTTP action catalog");
                }
                return approvedHost;
            }
        };
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    static boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        if (octets.length == 16) {
            return (octets[0] & 0xfe) == 0xfc; // IPv6 unique-local fc00::/7
        }
        int first = Byte.toUnsignedInt(octets[0]);
        int second = Byte.toUnsignedInt(octets[1]);
        int third = Byte.toUnsignedInt(octets[2]);
        return first == 0 || first >= 240
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    public static final class HttpTargetException extends RuntimeException {
        public HttpTargetException(String message) { super(message); }
        public HttpTargetException(String message, Throwable cause) { super(message, cause); }
    }
}
