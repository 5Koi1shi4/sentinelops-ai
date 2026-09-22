package io.sentinelops.api.incident.adapter.out.http;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.sentinelops.api.incident.application.evidence.EvidenceBudgetExceeded;
import io.sentinelops.api.incident.application.evidence.EvidenceSourceException;
import io.sentinelops.api.incident.application.evidence.EvidenceSourceRateLimited;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the only outbound HTTP client used by evidence adapters.
 *
 * <p>The request factory uses a no-redirect JDK client and a read timeout that
 * covers waiting for headers and consuming the response stream. Every response
 * is consumed at most up to {@code maxResponseBytes + 1} bytes and parsed only
 * after that bound has been enforced.</p>
 */
public final class BoundedRestClientFactory {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration DEFAULT_ATTEMPT_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    private final Duration connectTimeout;
    private final Duration attemptTimeout;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<EndpointKey, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EndpointKey, Retry> retries = new ConcurrentHashMap<>();
    private final RestClient client;

    public BoundedRestClientFactory() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_ATTEMPT_TIMEOUT);
    }

    /** Testable constructor; shorter positive values are permitted for focused tests. */
    public BoundedRestClientFactory(Duration connectTimeout, Duration attemptTimeout) {
        if (connectTimeout == null || attemptTimeout == null
                || connectTimeout.isNegative() || connectTimeout.isZero()
                || attemptTimeout.isNegative() || attemptTimeout.isZero()
                || connectTimeout.toMillis() < 1 || attemptTimeout.toMillis() < 1
                || connectTimeout.compareTo(DEFAULT_CONNECT_TIMEOUT) > 0
                || attemptTimeout.compareTo(DEFAULT_ATTEMPT_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeouts must be positive and no greater than defaults");
        }
        this.connectTimeout = connectTimeout;
        this.attemptTimeout = attemptTimeout;
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // Spring 7 wraps the response InputStream with this same deadline.
        requestFactory.setReadTimeout(attemptTimeout);
        requestFactory.enableCompression(false);
        // No observation registry is supplied: RestClient's transport observations stay disabled.
        this.client = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** Fetches and parses one JSON response from a configured provider URI. */
    public JsonNode get(URI uri, int maxResponseBytes) {
        validateUri(uri);
        validateResponseCap(maxResponseBytes);
        var endpoint = EndpointKey.from(uri);
        var retry = retries.computeIfAbsent(endpoint, ignored -> createRetry(endpoint));
        var circuitBreaker = circuitBreakers.computeIfAbsent(endpoint, ignored -> createCircuitBreaker(endpoint));
        Supplier<JsonNode> request = () -> getOnce(uri, maxResponseBytes);
        var decorated = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, request));
        try {
            return decorated.get();
        } catch (RetryableProviderFailure ignored) {
            throw new EvidenceSourceException("evidence provider unavailable");
        } catch (EvidenceSourceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // Do not attach provider exceptions: they may contain the URL or response body.
            throw new EvidenceSourceException("evidence provider request failed");
        }
    }

    private JsonNode getOnce(URI uri, int maxResponseBytes) {
        try {
            return client.get()
                    .uri(uri)
                    .header("Accept-Encoding", "identity")
                    .exchange((request, response) -> {
                        try {
                            return handleResponse(response, maxResponseBytes);
                        } catch (IOException failure) {
                            throw mapIoFailure(failure);
                        }
                    });
        } catch (RetryableProviderFailure failure) {
            throw failure;
        } catch (EvidenceSourceException failure) {
            throw failure;
        } catch (ResourceAccessException failure) {
            if (isTimeout(failure)) {
                throw new ProviderTimeoutFailure();
            }
            if (isConnectionReset(failure)) {
                throw new RetryableProviderFailure();
            }
            throw new EvidenceSourceException("evidence provider request failed");
        } catch (RestClientException failure) {
            throw new EvidenceSourceException("evidence provider request failed");
        } catch (RuntimeException failure) {
            throw new EvidenceSourceException("evidence provider request failed");
        }
    }

    private static RuntimeException mapIoFailure(IOException failure) {
        if (isTimeout(failure)) {
            return new ProviderTimeoutFailure();
        }
        if (isConnectionReset(failure)) {
            return new RetryableProviderFailure();
        }
        return new EvidenceSourceException("evidence provider request failed");
    }

    private JsonNode handleResponse(ClientHttpResponse response, int maxResponseBytes) throws IOException {
        var status = response.getStatusCode().value();
        if (status == 429) {
            closeQuietly(response);
            throw new EvidenceSourceRateLimited();
        }
        if (status >= 300 && status < 400) {
            closeQuietly(response);
            throw new EvidenceSourceException("evidence provider redirect rejected");
        }
        if (status == 502 || status == 503) {
            closeQuietly(response);
            throw new RetryableProviderFailure();
        }
        if (status >= 400) {
            closeQuietly(response);
            throw new EvidenceSourceException("evidence provider returned an error");
        }
        var body = readBody(response, maxResponseBytes);
        try {
            var json = objectMapper.readTree(body);
            if (json == null || json.isMissingNode()) {
                throw new EvidenceSourceException("evidence provider returned invalid JSON");
            }
            return json;
        } catch (JacksonException failure) {
            throw new EvidenceSourceException("evidence provider returned invalid JSON");
        }
    }

    private String readBody(ClientHttpResponse response, int maxResponseBytes) {
        var contentLength = response.getHeaders().getContentLength();
        if (contentLength > maxResponseBytes) {
            closeQuietly(response);
            throw new EvidenceBudgetExceeded("provider response exceeds byte budget");
        }
        var encoding = response.getHeaders().getFirst("Content-Encoding");
        if (encoding != null && !encoding.isBlank() && !encoding.equalsIgnoreCase("identity")) {
            closeQuietly(response);
            throw new EvidenceSourceException("unsupported provider content encoding");
        }
        try (InputStream body = response.getBody()) {
            var bytes = new ByteArrayOutputStream(Math.min(maxResponseBytes, 8192));
            var buffer = new byte[Math.min(maxResponseBytes + 1, 8192)];
            int total = 0;
            int read;
            while ((read = body.read(buffer, 0, Math.min(buffer.length, maxResponseBytes - total + 1))) != -1) {
                if (read > maxResponseBytes - total) {
                    throw new EvidenceBudgetExceeded("provider response exceeds byte budget");
                }
                bytes.write(buffer, 0, read);
                total += read;
            }
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (EvidenceBudgetExceeded failure) {
            throw failure;
        } catch (IOException failure) {
            if (isTimeout(failure)) {
                throw new ProviderTimeoutFailure();
            }
            if (isConnectionReset(failure)) {
                throw new RetryableProviderFailure();
            }
            throw new EvidenceSourceException("evidence provider response could not be read");
        }
    }

    private Retry createRetry(EndpointKey endpoint) {
        return Retry.of(
                "sentinelops-evidence-" + endpoint.safeName(),
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(Duration.ZERO)
                        .retryOnException(failure -> failure instanceof RetryableProviderFailure)
                        .build());
    }

    private CircuitBreaker createCircuitBreaker(EndpointKey endpoint) {
        return CircuitBreaker.of(
                "sentinelops-evidence-" + endpoint.safeName(),
                CircuitBreakerConfig.custom()
                        .minimumNumberOfCalls(4)
                        .slidingWindowSize(10)
                        .recordException(failure -> failure instanceof RetryableProviderFailure
                                || failure instanceof ProviderTimeoutFailure)
                        .build());
    }

    private static void validateUri(URI uri) {
        if (uri == null || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("provider URI must be an explicit http(s) URL");
        }
    }

    private static void validateResponseCap(int maxResponseBytes) {
        if (maxResponseBytes < 1 || maxResponseBytes > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("invalid provider response byte cap");
        }
    }

    private static void closeQuietly(ClientHttpResponse response) {
        try {
            response.close();
        } catch (RuntimeException ignored) {
            // Preserve the typed provider/status failure.
        }
    }

    private static boolean isTimeout(Throwable failure) {
        var current = failure;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            if (current instanceof java.util.concurrent.CancellationException
                    || current.getMessage() != null
                            && (current.getMessage().toLowerCase(Locale.ROOT).contains("timed out")
                                    || current.getMessage().toLowerCase(Locale.ROOT).contains("timeout")
                                    || current.getMessage().toLowerCase(Locale.ROOT).contains("request cancelled"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isConnectionReset(Throwable failure) {
        var current = failure;
        while (current != null) {
            if ((current instanceof SocketException || current instanceof IOException)
                    && current.getMessage() != null
                    && isResetMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isResetMessage(String message) {
        var normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("reset")
                || normalized.contains("header parser received no bytes")
                || normalized.contains("connection closed");
    }

    private record EndpointKey(String scheme, String host, int port, String path) {
        private static EndpointKey from(URI uri) {
            var path = uri.getRawPath();
            return new EndpointKey(
                    uri.getScheme().toLowerCase(Locale.ROOT),
                    uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort(),
                    path == null || path.isEmpty() ? "/" : path);
        }

        private String safeName() {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                var input = (scheme + "\u0000" + host + "\u0000" + port + "\u0000" + path)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return java.util.HexFormat.of().formatHex(digest.digest(input));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
            }
        }
    }

    private static final class RetryableProviderFailure extends RuntimeException {
        private RetryableProviderFailure() {
            super("transient provider failure");
        }
    }

    private static final class ProviderTimeoutFailure extends EvidenceSourceException {
        private ProviderTimeoutFailure() {
            super("evidence provider timed out");
        }
    }
}
