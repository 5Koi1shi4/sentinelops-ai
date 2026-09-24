package io.sentinelops.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import io.sentinelops.executor.controlplane.ControlPlaneClient;
import io.sentinelops.executor.controlplane.ExecutorOAuth2TokenProvider;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.ExecutionStepResult;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.RunbookAdapter;
import io.sentinelops.executor.runbook.RunbookDispatcher;
import io.sentinelops.executor.stream.ExecutionLeaseHeartbeat;
import io.sentinelops.executor.stream.ExecutionMessage;
import io.sentinelops.executor.stream.ExecutionMessageAcknowledger;
import io.sentinelops.executor.stream.ExecutionMessageListener;
import io.sentinelops.executor.ticket.ExecutionTicketVerifier;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class ExecutorCrashRecoveryIT {

    @Test
    void persistsPreparedAndDispatchedBeforeCallingTheAdapter() {
        var scenario = new Scenario();
        scenario.claim();
        scenario.phase("prepared", HttpStatus.OK);
        scenario.phase("dispatched", HttpStatus.OK);
        scenario.complete();

        scenario.listener.onMessage(scenario.message);

        scenario.server.verify();
        assertThat(scenario.sideEffects.get()).isOne();
    }

    @Test
    void rejectedPreparedPhasePreventsAnySideEffect() {
        var scenario = new Scenario();
        scenario.claim();
        scenario.phase("prepared", HttpStatus.SERVICE_UNAVAILABLE);

        assertThatThrownBy(() -> scenario.listener.onMessage(scenario.message))
                .isInstanceOf(ControlPlaneClient.TransientControlPlaneException.class);

        scenario.server.verify();
        assertThat(scenario.sideEffects.get()).isZero();
    }

    private static final class Scenario {
        private final UUID executionId = UUID.randomUUID();
        private final ExecutionMessage message =
                new ExecutionMessage("1710000000100-0", UUID.randomUUID(), executionId);
        private final MockRestServiceServer server;
        private final ExecutionMessageListener listener;
        private final AtomicInteger sideEffects = new AtomicInteger();

        private Scenario() {
            var builder = RestClient.builder().baseUrl("https://control.example.test");
            server = MockRestServiceServer.bindTo(builder).build();
            var tokens = mock(ExecutorOAuth2TokenProvider.class);
            when(tokens.accessToken("sentinelops-api", "")).thenReturn("api-token");
            var control = new ControlPlaneClient(builder.build(), tokens,
                    new ObjectMapper(), "sentinelops-api", Duration.ofMinutes(5));
            var verifier = mock(ExecutionTicketVerifier.class);
            when(verifier.verify("ticket", executionId)).thenReturn(new AuthorizedRunbookStep(
                    executionId, UUID.randomUUID(), UUID.randomUUID(), "checksum",
                    "recover-one", "recover_connection_pool", "demo-http",
                    Map.of("replicas", 1), "demo-checkout", "R1", 1));
            var heartbeat = mock(ExecutionLeaseHeartbeat.class);
            var lease = mock(ExecutionLeaseHeartbeat.ActiveLease.class);
            when(heartbeat.start(any(), any(), any())).thenReturn(lease);
            when(lease.ticketForResult()).thenReturn("ticket");
            doAnswer(invocation -> {
                Consumer<String> action = invocation.getArgument(0);
                action.accept("ticket");
                return null;
            }).when(lease).withCurrentTicket(any());
            RunbookAdapter adapter = new RunbookAdapter() {
                @Override public String adapterId() { return "demo-http"; }
                @Override public Set<String> supportedOperations() {
                    return Set.of("recover_connection_pool");
                }
                @Override public ExecutionStepResult execute(
                        AuthorizedRunbookStep step, IdempotencyContext context) {
                    sideEffects.incrementAndGet();
                    return ExecutionStepResult.succeeded(
                            "demo-v1", "request-hash", Map.of("changed", true));
                }
            };
            listener = new ExecutionMessageListener(control, verifier,
                    new RunbookDispatcher(java.util.List.of(adapter)), heartbeat,
                    mock(ExecutionMessageAcknowledger.class));
        }

        private void claim() {
            server.expect(requestTo(url(":claim")))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.OK)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("""
                                    {"executionId":"%s","fencingToken":1,
                                     "leaseUntil":"2099-09-20T12:00:30Z","ticket":"ticket"}
                                    """.formatted(executionId)));
        }

        private void phase(String phase, HttpStatus status) {
            server.expect(requestTo(url(":attempt-events")))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().json("""
                            {"fencingToken":1,"stepId":"recover-one",
                             "attemptNo":1,"phase":"%s","metadata":{}}
                            """.formatted(phase)))
                    .andRespond(withStatus(status)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(status == HttpStatus.OK
                                    ? "{\"eventId\":\"%s\",\"executionId\":\"%s\",\"phase\":\"%s\"}"
                                            .formatted(UUID.randomUUID(), executionId, phase)
                                    : "{\"errorCode\":\"dependency_unavailable\"}"));
        }

        private void complete() {
            server.expect(requestTo(url(":complete")))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.OK));
        }

        private String url(String action) {
            return "https://control.example.test/internal/v1/executions/"
                    + executionId + action;
        }
    }
}
