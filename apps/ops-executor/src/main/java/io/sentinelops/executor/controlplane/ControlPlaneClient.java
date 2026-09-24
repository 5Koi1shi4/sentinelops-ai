package io.sentinelops.executor.controlplane;

import com.nimbusds.jose.jwk.JWKSet;
import io.sentinelops.executor.ticket.ExecutionJwkProvider;
import java.text.ParseException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ControlPlaneClient implements ExecutionJwkProvider {

    private static final Set<String> TERMINAL_CONFLICT_CODES = Set.of(
            "execution_not_claimable",
            "execution_authorization_invalidated",
            "execution_outcome_unknown",
            "execution_not_running");

    private final RestClient client;
    private final ExecutorOAuth2TokenProvider tokens;
    private final ObjectMapper objectMapper;
    private final String audience;
    private final Duration jwksCacheTtl;
    private volatile CachedJwks cachedJwks;

    @Autowired
    public ControlPlaneClient(
            RestClient.Builder restClient,
            ExecutorOAuth2TokenProvider tokens,
            ObjectMapper objectMapper,
            @Value("${sentinelops.control-plane.base-url}") String baseUrl,
            @Value("${sentinelops.control-plane.audience}") String audience,
            @Value("${sentinelops.control-plane.jwks-cache-ttl:PT5M}")
                    Duration jwksCacheTtl) {
        this(
                configuredClient(restClient, baseUrl),
                tokens,
                objectMapper,
                audience,
                jwksCacheTtl);
    }

    public ControlPlaneClient(
            RestClient client,
            ExecutorOAuth2TokenProvider tokens,
            ObjectMapper objectMapper,
            String audience,
            Duration jwksCacheTtl) {
        this.client = Objects.requireNonNull(client, "client");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.audience = requireText(audience, "audience");
        this.jwksCacheTtl = Objects.requireNonNull(jwksCacheTtl, "jwksCacheTtl");
        if (jwksCacheTtl.isNegative() || jwksCacheTtl.isZero()) {
            throw new IllegalArgumentException("jwksCacheTtl must be positive");
        }
    }

    public ClaimedExecution claim(UUID executionId, String idempotencyKey) {
        Objects.requireNonNull(executionId, "executionId");
        try {
            var response = client
                    .post()
                    .uri("/internal/v1/executions/{id}:claim", executionId)
                    .headers(headers -> {
                        headers.setBearerAuth(apiToken());
                        headers.set("Idempotency-Key", requireText(
                                idempotencyKey, "idempotencyKey"));
                    })
                    .retrieve()
                    .body(ClaimedExecution.class);
            return Objects.requireNonNull(response, "claim response");
        } catch (RestClientResponseException failure) {
            throw classify(failure);
        } catch (ResourceAccessException failure) {
            throw new TransientControlPlaneException("Control plane is unavailable", failure);
        }
    }

    public void complete(
            UUID executionId,
            String executionTicket,
            AttemptReport report,
            String idempotencyKey) {
        reportAttempt(
                executionId, executionTicket, report, idempotencyKey, "complete");
    }

    public void recordPhase(
            UUID executionId,
            String executionTicket,
            long fencingToken,
            String stepId,
            int attemptNo,
            String phase,
            String idempotencyKey) {
        Objects.requireNonNull(executionId, "executionId");
        if (fencingToken <= 0 || attemptNo <= 0) {
            throw new IllegalArgumentException("fencingToken and attemptNo must be positive");
        }
        try {
            client.post()
                    .uri("/internal/v1/executions/{id}:attempt-events", executionId)
                    .headers(headers -> {
                        headers.setBearerAuth(apiToken());
                        headers.set("Idempotency-Key", requireText(idempotencyKey, "idempotencyKey"));
                        headers.set("X-SentinelOps-Execution-Ticket",
                                requireText(executionTicket, "executionTicket"));
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "fencingToken", fencingToken,
                            "stepId", requireText(stepId, "stepId"),
                            "attemptNo", attemptNo,
                            "phase", requireText(phase, "phase"),
                            "metadata", Map.of()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException failure) {
            throw classify(failure);
        } catch (ResourceAccessException failure) {
            throw new TransientControlPlaneException("Control plane is unavailable", failure);
        }
    }

    public HeartbeatLease heartbeat(
            UUID executionId,
            String executionTicket,
            long fencingToken,
            String idempotencyKey) {
        Objects.requireNonNull(executionId, "executionId");
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        try {
            var response = client.post()
                    .uri("/internal/v1/executions/{id}:heartbeat", executionId)
                    .headers(headers -> {
                        headers.setBearerAuth(apiToken());
                        headers.set("Idempotency-Key", requireText(
                                idempotencyKey, "idempotencyKey"));
                        headers.set(
                                "X-SentinelOps-Execution-Ticket",
                                requireText(executionTicket, "executionTicket"));
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("fencingToken", fencingToken))
                    .retrieve()
                    .body(HeartbeatLease.class);
            return Objects.requireNonNull(response, "heartbeat response");
        } catch (RestClientResponseException failure) {
            throw classify(failure);
        } catch (ResourceAccessException failure) {
            throw new TransientControlPlaneException("Control plane is unavailable", failure);
        }
    }

    public void fail(
            UUID executionId,
            String executionTicket,
            AttemptReport report,
            String idempotencyKey) {
        reportAttempt(executionId, executionTicket, report, idempotencyKey, "fail");
    }

    private void reportAttempt(
            UUID executionId,
            String executionTicket,
            AttemptReport report,
            String idempotencyKey,
            String action) {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(report, "report");
        try {
            client.post()
                    .uri("/internal/v1/executions/{id}:" + action, executionId)
                    .headers(headers -> {
                        headers.setBearerAuth(apiToken());
                        headers.set("Idempotency-Key", requireText(
                                idempotencyKey, "idempotencyKey"));
                        headers.set(
                                "X-SentinelOps-Execution-Ticket",
                                requireText(executionTicket, "executionTicket"));
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(report)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException failure) {
            throw classify(failure);
        } catch (ResourceAccessException failure) {
            throw new TransientControlPlaneException("Control plane is unavailable", failure);
        }
    }

    @Override
    public JWKSet load() {
        var cached = cachedJwks;
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.keys();
        }
        return refresh();
    }

    @Override
    public synchronized JWKSet refresh() {
        try {
            String body = client
                    .get()
                    .uri("/internal/v1/execution-keys/jwks.json")
                    .headers(headers -> headers.setBearerAuth(apiToken()))
                    .retrieve()
                    .body(String.class);
            var keys = JWKSet.parse(Objects.requireNonNull(body, "JWKS response"));
            cachedJwks = new CachedJwks(keys, Instant.now().plus(jwksCacheTtl));
            return keys;
        } catch (RestClientResponseException failure) {
            throw classify(failure);
        } catch (ResourceAccessException failure) {
            throw new TransientControlPlaneException("Control plane is unavailable", failure);
        } catch (ParseException | NullPointerException failure) {
            throw new ControlPlaneProtocolException("Control plane returned invalid JWKS", failure);
        }
    }

    private RuntimeException classify(RestClientResponseException failure) {
        int status = failure.getStatusCode().value();
        String code = errorCode(failure.getResponseBodyAsString());
        if (status == 401 || status == 403) {
            return new ControlPlaneAuthorizationException(
                    "Control plane rejected the executor identity");
        }
        if (status == 429 || status >= 500) {
            return new TransientControlPlaneException("Control plane is temporarily unavailable");
        }
        if (status == 409 && "execution_lease_active".equals(code)) {
            return new TransientControlPlaneException("Execution lease is still active");
        }
        if (status == 409 && "STALE_FENCING_TOKEN".equals(code)) {
            return new StaleFencingTokenException(code, "Execution lease is stale");
        }
        if (isTerminal(status, code)) {
            return new TerminalControlPlaneException(code, "Execution is no longer actionable");
        }
        return new ControlPlaneProtocolException(
                "Control plane rejected a non-terminal command", failure);
    }

    private boolean isTerminal(int status, String code) {
        return (status == 409 && TERMINAL_CONFLICT_CODES.contains(code))
                || (status == 404 && "execution_not_found".equals(code));
    }

    private String errorCode(String body) {
        try {
            return objectMapper.readTree(body).path("errorCode").asString("control_plane_rejected");
        } catch (RuntimeException malformed) {
            return "control_plane_rejected";
        }
    }

    private String apiToken() {
        return tokens.accessToken(audience, "");
    }

    private static RestClient configuredClient(RestClient.Builder builder, String baseUrl) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(requestFactory)
                .baseUrl(requireText(baseUrl, "baseUrl"))
                .build();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record ClaimedExecution(
            UUID executionId, long fencingToken, Instant leaseUntil, String ticket) {

        public ClaimedExecution {
            Objects.requireNonNull(executionId, "executionId");
            if (fencingToken <= 0) {
                throw new IllegalArgumentException("fencingToken must be positive");
            }
            Objects.requireNonNull(leaseUntil, "leaseUntil");
            ticket = requireText(ticket, "ticket");
        }
    }

    public record HeartbeatLease(
            UUID executionId, long fencingToken, Instant leaseUntil, String ticket) {

        public HeartbeatLease {
            Objects.requireNonNull(executionId, "executionId");
            if (fencingToken <= 0) {
                throw new IllegalArgumentException("fencingToken must be positive");
            }
            Objects.requireNonNull(leaseUntil, "leaseUntil");
            ticket = requireText(ticket, "ticket");
        }
    }

    public record AttemptReport(
            long fencingToken,
            String stepId,
            int attemptNo,
            String adapterId,
            String adapterVersion,
            String requestHash,
            Map<String, Object> sanitizedResult) {

        public AttemptReport {
            if (fencingToken <= 0 || attemptNo <= 0) {
                throw new IllegalArgumentException(
                        "fencingToken and attemptNo must be positive");
            }
            stepId = requireText(stepId, "stepId");
            adapterId = requireText(adapterId, "adapterId");
            adapterVersion = requireText(adapterVersion, "adapterVersion");
            requestHash = requireText(requestHash, "requestHash");
            sanitizedResult = Map.copyOf(
                    Objects.requireNonNull(sanitizedResult, "sanitizedResult"));
        }
    }

    private record CachedJwks(JWKSet keys, Instant expiresAt) {}

    public static class TerminalControlPlaneException extends RuntimeException {

        private final String errorCode;

        public TerminalControlPlaneException(String errorCode, String message) {
            super(message);
            this.errorCode = requireText(errorCode, "errorCode");
        }

        public String errorCode() {
            return errorCode;
        }
    }

    public static final class StaleFencingTokenException extends RuntimeException {

        private final String errorCode;

        public StaleFencingTokenException(String errorCode, String message) {
            super(message);
            this.errorCode = requireText(errorCode, "errorCode");
        }

        public String errorCode() {
            return errorCode;
        }
    }

    public static final class ControlPlaneAuthorizationException extends RuntimeException {

        public ControlPlaneAuthorizationException(String message) {
            super(message);
        }
    }

    public static class TransientControlPlaneException extends RuntimeException {

        public TransientControlPlaneException(String message) {
            super(message);
        }

        public TransientControlPlaneException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ControlPlaneProtocolException extends RuntimeException {

        public ControlPlaneProtocolException(String message) {
            super(message);
        }

        public ControlPlaneProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
