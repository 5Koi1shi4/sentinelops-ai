package io.sentinelops.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import io.sentinelops.executor.controlplane.ControlPlaneClient;
import io.sentinelops.executor.controlplane.ControlPlaneClient.AttemptReport;
import io.sentinelops.executor.controlplane.ExecutorOAuth2TokenProvider;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class ControlPlaneClientTest {

    private MockRestServiceServer server;
    private ControlPlaneClient client;

    @BeforeEach
    void createClient() {
        var builder = RestClient.builder().baseUrl("https://control.example.test");
        server = MockRestServiceServer.bindTo(builder).build();
        var tokens = mock(ExecutorOAuth2TokenProvider.class);
        when(tokens.accessToken("sentinelops-api", "")).thenReturn("api-token");
        client = new ControlPlaneClient(
                builder.build(),
                tokens,
                new ObjectMapper(),
                "sentinelops-api",
                Duration.ofMinutes(5));
    }

    @Test
    void mapsClaimConflictToTerminalRejection() {
        UUID executionId = UUID.randomUUID();
        expectClaim(executionId, HttpStatus.CONFLICT, "execution_not_claimable");

        assertThatThrownBy(() -> client.claim(executionId, "record-1"))
                .isInstanceOf(ControlPlaneClient.TerminalControlPlaneException.class)
                .hasMessageContaining("no longer actionable");
        server.verify();
    }

    @Test
    void activeLeaseConflictRemainsRetryable() {
        UUID executionId = UUID.randomUUID();
        expectClaim(executionId, HttpStatus.CONFLICT, "execution_lease_active");

        assertThatThrownBy(() -> client.claim(executionId, "record-active"))
                .isInstanceOf(ControlPlaneClient.TransientControlPlaneException.class);
        server.verify();
    }

    @Test
    void mapsStaleCompletionSeparatelyFromTransientFailures() {
        UUID executionId = UUID.randomUUID();
        server.expect(requestTo("https://control.example.test/internal/v1/executions/"
                        + executionId + ":complete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer api-token"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"errorCode\":\"STALE_FENCING_TOKEN\"}"));

        assertThatThrownBy(() -> client.complete(
                        executionId, "ticket", report(), "record-2:complete"))
                .isInstanceOf(ControlPlaneClient.StaleFencingTokenException.class)
                .isNotInstanceOf(ControlPlaneClient.TerminalControlPlaneException.class);
        server.verify();
    }

    @Test
    void mapsServerFailureToTransientFailure() {
        UUID executionId = UUID.randomUUID();
        expectClaim(executionId, HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable");

        assertThatThrownBy(() -> client.claim(executionId, "record-3"))
                .isInstanceOf(ControlPlaneClient.TransientControlPlaneException.class);
        server.verify();
    }

    @Test
    void serverFailureWinsOverATerminalLookingErrorCode() {
        UUID executionId = UUID.randomUUID();
        expectClaim(executionId, HttpStatus.SERVICE_UNAVAILABLE, "execution_not_claimable");

        assertThatThrownBy(() -> client.claim(executionId, "record-503"))
                .isInstanceOf(ControlPlaneClient.TransientControlPlaneException.class);
        server.verify();
    }

    @Test
    void sendsFencedHeartbeatWithItsOwnIdempotencyKey() {
        UUID executionId = UUID.randomUUID();
        server.expect(requestTo("https://control.example.test/internal/v1/executions/"
                        + executionId + ":heartbeat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer api-token"))
                .andExpect(header("Idempotency-Key", "record-heartbeat"))
                .andExpect(header("X-SentinelOps-Execution-Ticket", "ticket"))
                .andExpect(content().json("{\"fencingToken\":7}"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"executionId":"%s","fencingToken":7,
                                 "leaseUntil":"2026-09-20T12:00:30Z",
                                 "ticket":"rotated-ticket"}
                                """.formatted(executionId)));

        var heartbeat = client.heartbeat(
                executionId, "ticket", 7, "record-heartbeat");

        assertThat(heartbeat.executionId()).isEqualTo(executionId);
        assertThat(heartbeat.fencingToken()).isEqualTo(7);
        assertThat(heartbeat.ticket()).isEqualTo("rotated-ticket");
        server.verify();
    }

    @Test
    void mapsAuthenticationFailureSeparately() {
        UUID executionId = UUID.randomUUID();
        expectClaim(executionId, HttpStatus.FORBIDDEN, "access_denied");

        assertThatThrownBy(() -> client.claim(executionId, "record-4"))
                .isInstanceOf(ControlPlaneClient.ControlPlaneAuthorizationException.class);
        server.verify();
    }

    @Test
    void ordinaryClientErrorIsNotAcknowledgeableAsATerminalState() {
        UUID executionId = UUID.randomUUID();
        expectClaim(executionId, HttpStatus.BAD_REQUEST, "invalid_request");

        assertThatThrownBy(() -> client.claim(executionId, "record-5"))
                .isInstanceOf(ControlPlaneClient.ControlPlaneProtocolException.class);
        server.verify();
    }

    @Test
    void unknownConflictIsNotAcknowledgeableAsATerminalState() {
        UUID executionId = UUID.randomUUID();
        expectClaim(executionId, HttpStatus.CONFLICT, "idempotency_conflict");

        assertThatThrownBy(() -> client.claim(executionId, "record-6"))
                .isInstanceOf(ControlPlaneClient.ControlPlaneProtocolException.class);
        server.verify();
    }

    private void expectClaim(UUID executionId, HttpStatus status, String errorCode) {
        server.expect(requestTo("https://control.example.test/internal/v1/executions/"
                        + executionId + ":claim"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer api-token"))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"errorCode\":\"" + errorCode + "\"}"));
    }

    private AttemptReport report() {
        return new AttemptReport(
                1,
                "recover-one",
                1,
                "demo-http",
                "1.0.0",
                "request-hash",
                Map.of("changed", true));
    }
}
