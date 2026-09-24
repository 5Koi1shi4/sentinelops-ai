package io.sentinelops.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.sentinelops.executor.controlplane.ControlPlaneClient;
import io.sentinelops.executor.controlplane.ControlPlaneClient.ClaimedExecution;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.ExecutionStepResult;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.RunbookAdapter;
import io.sentinelops.executor.runbook.RunbookDispatcher;
import io.sentinelops.executor.runbook.StepIdempotency;
import io.sentinelops.executor.stream.ExecutionLeaseHeartbeat;
import io.sentinelops.executor.stream.ExecutionMessage;
import io.sentinelops.executor.stream.ExecutionMessageAcknowledger;
import io.sentinelops.executor.stream.ExecutionMessageListener;
import io.sentinelops.executor.ticket.ExecutionTicketVerifier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class UnknownOutcomePolicyTest {

    @Test
    void onlyTheFencedDemoOperationHasProvenReplaySemantics() {
        UUID executionId = UUID.randomUUID();
        assertThat(StepIdempotency.mayReplayAfterDispatch(
                step(executionId, "demo-http", "recover_connection_pool"))).isTrue();
        assertThat(StepIdempotency.mayReplayAfterDispatch(
                step(executionId, "demo-http", "one_shot"))).isFalse();
        assertThat(StepIdempotency.mayReplayAfterDispatch(
                step(executionId, "once-test", "one_shot"))).isFalse();
        assertThat(StepIdempotency.mayReplayAfterDispatch(
                step(executionId, "demo-http", "recover_connection_pool", "other-target")))
                .isFalse();
    }

    @Test
    void nonIdempotentTimeoutReportsUnknownAndStopsWithoutRetry() {
        var scenario = new Scenario("once-test", "one_shot");

        scenario.listener.onMessage(scenario.message);

        verify(scenario.control).recordPhase(eq(scenario.executionId), eq("ticket"),
                eq(1L), eq("recover-one"), eq(1),
                eq("unknown_after_dispatch"), any());
        verify(scenario.acknowledger).acknowledge(scenario.message);
    }

    @Test
    void replaySafeTimeoutLeavesTheRecordPendingForAReclaim() {
        var scenario = new Scenario("demo-http", "recover_connection_pool");

        assertThatThrownBy(() -> scenario.listener.onMessage(scenario.message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("target timed out");

        verify(scenario.control).recordPhase(eq(scenario.executionId), eq("ticket"),
                eq(1L), eq("recover-one"), eq(1),
                eq("unknown_after_dispatch"), any());
        org.mockito.Mockito.verifyNoInteractions(scenario.acknowledger);
    }

    private static AuthorizedRunbookStep step(UUID executionId, String adapterId,
            String operation) {
        return step(executionId, adapterId, operation, "demo-checkout");
    }

    private static AuthorizedRunbookStep step(UUID executionId, String adapterId,
            String operation, String targetAlias) {
        return new AuthorizedRunbookStep(executionId, UUID.randomUUID(),
                UUID.randomUUID(), "checksum", "recover-one", operation,
                adapterId, Map.of("replicas", 1), targetAlias, "R1", 1);
    }

    private static final class Scenario {
        private final UUID executionId = UUID.randomUUID();
        private final ExecutionMessage message = new ExecutionMessage(
                "1710000000110-0", UUID.randomUUID(), executionId);
        private final ControlPlaneClient control = mock(ControlPlaneClient.class);
        private final ExecutionMessageAcknowledger acknowledger =
                mock(ExecutionMessageAcknowledger.class);
        private final ExecutionMessageListener listener;

        private Scenario(String adapterId, String operation) {
            var claim = new ClaimedExecution(executionId, 1,
                    Instant.now().plusSeconds(30), "ticket");
            when(control.claim(eq(executionId), any())).thenReturn(claim);
            var verifier = mock(ExecutionTicketVerifier.class);
            when(verifier.verify("ticket", executionId))
                    .thenReturn(step(executionId, adapterId, operation));
            var heartbeat = mock(ExecutionLeaseHeartbeat.class);
            var lease = mock(ExecutionLeaseHeartbeat.ActiveLease.class);
            when(heartbeat.start(any(), any(), any())).thenReturn(lease);
            doAnswer(invocation -> {
                Consumer<String> action = invocation.getArgument(0);
                action.accept("ticket");
                return null;
            }).when(lease).withCurrentTicket(any());
            RunbookAdapter adapter = new RunbookAdapter() {
                @Override public String adapterId() { return adapterId; }
                @Override public Set<String> supportedOperations() { return Set.of(operation); }
                @Override public ExecutionStepResult execute(
                        AuthorizedRunbookStep step, IdempotencyContext context) {
                    throw new IllegalStateException("target timed out");
                }
            };
            listener = new ExecutionMessageListener(control, verifier,
                    new RunbookDispatcher(List.of(adapter)), heartbeat, acknowledger);
        }
    }
}
