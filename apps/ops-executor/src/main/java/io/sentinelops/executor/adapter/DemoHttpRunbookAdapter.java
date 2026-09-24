package io.sentinelops.executor.adapter;

import io.sentinelops.executor.controlplane.ExecutorOAuth2TokenProvider;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.ExecutionStepResult;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.RunbookAdapter;
import io.sentinelops.executor.runbook.UnsupportedRunbookStepException;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public final class DemoHttpRunbookAdapter implements RunbookAdapter {

    private static final String ADAPTER_ID = "demo-http";
    private static final String ADAPTER_VERSION = "demo-http-v1";
    private static final String OPERATION = "recover_connection_pool";
    private static final String RECOVERY_PATH =
            "/internal/runbooks/recover-connection-pool";

    private final RestClient client;
    private final ExecutorOAuth2TokenProvider tokens;
    private final String targetAlias;
    private final String audience;
    private final String scope;

    @Autowired
    public DemoHttpRunbookAdapter(
            RestClient.Builder builder,
            ExecutorOAuth2TokenProvider tokens,
            @Value("${sentinelops.targets.demo-checkout.base-url}") String baseUrl,
            @Value("${sentinelops.targets.demo-checkout.audience}") String audience,
            @Value("${sentinelops.targets.demo-checkout.scope}") String scope) {
        this(
                configuredClient(builder, baseUrl),
                tokens,
                "demo-checkout",
                audience,
                scope);
    }

    public DemoHttpRunbookAdapter(
            RestClient client,
            ExecutorOAuth2TokenProvider tokens,
            String targetAlias,
            String audience,
            String scope) {
        this.client = Objects.requireNonNull(client, "client");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.targetAlias = requireText(targetAlias, "targetAlias");
        this.audience = requireText(audience, "audience");
        this.scope = requireText(scope, "scope");
    }

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public Set<String> supportedOperations() {
        return Set.of(OPERATION);
    }

    @Override
    public ExecutionStepResult execute(
            AuthorizedRunbookStep step, IdempotencyContext context) {
        return execute(step, context, () -> {});
    }

    @Override
    public ExecutionStepResult execute(
            AuthorizedRunbookStep step,
            IdempotencyContext context,
            Runnable beforeTransport) {
        validate(step, context);
        Objects.requireNonNull(beforeTransport, "beforeTransport");
        String requestHash = sha256(
                ADAPTER_VERSION + '|' + targetAlias + '|' + OPERATION + "|replicas=1");
        try {
            String accessToken = tokens.accessToken(audience, scope);
            var request = client
                    .post()
                    .uri(RECOVERY_PATH)
                    .headers(headers -> {
                        headers.setBearerAuth(accessToken);
                        headers.set("Idempotency-Key", context.key());
                        headers.set(
                                "X-SentinelOps-Fencing-Token",
                                Long.toString(context.fencingToken()));
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("replicas", 1));
            beforeTransport.run();
            var response = request.retrieve().toEntity(RecoveryResponse.class);
            var body = Objects.requireNonNull(
                    response.getBody(), "Demo recovery response body");
            if (body.fencingToken() != context.fencingToken()) {
                throw new RunbookTargetException(
                        "Demo recovery response fencing token does not match the request");
            }
            return ExecutionStepResult.succeeded(
                    ADAPTER_VERSION,
                    requestHash,
                    Map.of(
                            "changed", body.changed(),
                            "httpStatus", response.getStatusCode().value()));
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == 429 || status >= 500) {
                throw new RunbookTargetException(
                        "Demo recovery target is temporarily unavailable", failure);
            }
            return ExecutionStepResult.failed(
                    ADAPTER_VERSION,
                    requestHash,
                    Map.of("errorCode", "target_rejected", "httpStatus", status));
        } catch (ResourceAccessException failure) {
            throw new RunbookTargetException(
                    "Demo recovery target is unavailable", failure);
        }
    }

    private void validate(AuthorizedRunbookStep step, IdempotencyContext context) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(context, "context");
        boolean matchingContext = step.executionId().equals(context.executionId())
                && step.stepId().equals(context.stepId())
                && step.fencingToken() == context.fencingToken();
        Object replicas = step.parameters().get("replicas");
        boolean exactParameters = step.parameters().size() == 1
                && isIntegerOne(replicas);
        if (!ADAPTER_ID.equals(step.adapterId())
                || !OPERATION.equals(step.operation())
                || !targetAlias.equals(step.target())
                || !matchingContext
                || !exactParameters) {
            throw new UnsupportedRunbookStepException(
                    "The signed Runbook step is not allowlisted by the Demo HTTP adapter");
        }
    }

    private boolean isIntegerOne(Object value) {
        return (value instanceof Byte
                        || value instanceof Short
                        || value instanceof Integer
                        || value instanceof Long
                        || value instanceof BigInteger)
                && new BigInteger(value.toString()).equals(BigInteger.ONE);
    }

    private static RestClient configuredClient(
            RestClient.Builder builder, String baseUrl) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(requestFactory)
                .baseUrl(requireText(baseUrl, "baseUrl"))
                .build();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record RecoveryResponse(boolean changed, long fencingToken) {}

    public static final class RunbookTargetException extends RuntimeException {

        public RunbookTargetException(String message) {
            super(message);
        }

        public RunbookTargetException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
