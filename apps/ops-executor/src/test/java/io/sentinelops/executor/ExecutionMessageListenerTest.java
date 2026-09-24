package io.sentinelops.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import io.sentinelops.executor.controlplane.ControlPlaneClient;
import io.sentinelops.executor.controlplane.ControlPlaneClient.ClaimedExecution;
import io.sentinelops.executor.controlplane.ControlPlaneClient.TerminalControlPlaneException;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.ExecutionStepResult;
import io.sentinelops.executor.runbook.RunbookAdapter;
import io.sentinelops.executor.runbook.RunbookDispatcher;
import io.sentinelops.executor.stream.ExecutionMessage;
import io.sentinelops.executor.stream.ExecutionMessageAcknowledger;
import io.sentinelops.executor.stream.ExecutionLeaseHeartbeat;
import io.sentinelops.executor.stream.ExecutionLeaseHeartbeat.ActiveLease;
import io.sentinelops.executor.stream.ExecutionMessageListener;
import io.sentinelops.executor.ticket.ExecutionTicketVerifier;
import io.sentinelops.executor.ticket.InvalidExecutionTicket;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExecutionMessageListenerTest {

    private final ControlPlaneClient controlPlane = mock(ControlPlaneClient.class);
    private final ExecutionTicketVerifier verifier = mock(ExecutionTicketVerifier.class);
    private final RunbookAdapter adapter = mock(RunbookAdapter.class);
    private final ExecutionMessageAcknowledger acknowledger =
            mock(ExecutionMessageAcknowledger.class);
    private final ExecutionLeaseHeartbeat leaseHeartbeat = mock(ExecutionLeaseHeartbeat.class);
    private final ActiveLease activeLease = mock(ActiveLease.class);

    private ExecutionMessageListener listener;

    @BeforeEach
    void configureListener() {
        when(adapter.adapterId()).thenReturn("demo-http");
        when(adapter.supportedOperations()).thenReturn(Set.of("recover_connection_pool"));
        when(leaseHeartbeat.start(any(), any(), any(String.class))).thenReturn(activeLease);
        when(activeLease.ticketForResult()).thenReturn("signed-ticket");
        doAnswer(invocation -> {
            Consumer<String> action = invocation.getArgument(0);
            action.accept("signed-ticket");
            return null;
        }).when(activeLease).withCurrentTicket(any());
        when(adapter.execute(any(), any(), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return ExecutionStepResult.succeeded(
                    "1.0.0", "request-hash", Map.of("changed", true));
        });
        listener = new ExecutionMessageListener(
                controlPlane,
                verifier,
                new RunbookDispatcher(java.util.List.of(adapter)),
                leaseHeartbeat,
                acknowledger);
    }

    @Test
    void duplicateStreamMessageDoesNotDispatchTwice() {
        UUID executionId = UUID.randomUUID();
        var firstDelivery = new ExecutionMessage("1710000000000-0", UUID.randomUUID(), executionId);
        var duplicateDelivery =
                new ExecutionMessage("1710000000001-0", firstDelivery.eventId(), executionId);
        var claim = new ClaimedExecution(
                executionId, 1, Instant.now().plusSeconds(30), "signed-ticket");
        var step = authorizedStep(executionId);
        when(controlPlane.claim(eq(executionId), any(String.class)))
                .thenReturn(claim)
                .thenThrow(new TerminalControlPlaneException(
                        "execution_not_claimable", "Execution already completed"));
        when(verifier.verify("signed-ticket", executionId)).thenReturn(step);
        when(adapter.execute(any(), any()))
                .thenReturn(ExecutionStepResult.succeeded(
                        "1.0.0", "request-hash", Map.of("changed", true)));

        listener.onMessage(firstDelivery);
        listener.onMessage(duplicateDelivery);

        verify(adapter, times(1)).execute(any(), any(), any(Runnable.class));
        verify(controlPlane, times(2)).claim(any(), any());
        verify(controlPlane, times(1)).complete(any(), any(), any(), any());
        var ordered = inOrder(leaseHeartbeat, adapter, activeLease, controlPlane);
        ordered.verify(leaseHeartbeat).start(eq(firstDelivery), eq(claim), any(String.class));
        ordered.verify(adapter).execute(any(), any(), any(Runnable.class));
        ordered.verify(activeLease).ticketForResult();
        ordered.verify(controlPlane).complete(any(), any(), any(), any());
        ordered.verify(activeLease).close();
        verify(acknowledger).acknowledge(firstDelivery);
        verify(acknowledger).acknowledge(duplicateDelivery);
    }

    @Test
    void redeliveredPendingRecordUsesAFreshClaimAttemptKey() {
        UUID executionId = UUID.randomUUID();
        var message = new ExecutionMessage("1710000000004-0", UUID.randomUUID(), executionId);
        var claim = new ClaimedExecution(
                executionId, 1, Instant.now().plusSeconds(30), "signed-ticket");
        when(controlPlane.claim(eq(executionId), any(String.class)))
                .thenReturn(claim)
                .thenThrow(new TerminalControlPlaneException(
                        "execution_not_claimable", "Execution already completed"));
        when(verifier.verify("signed-ticket", executionId)).thenReturn(authorizedStep(executionId));
        when(adapter.execute(any(), any()))
                .thenReturn(ExecutionStepResult.succeeded(
                        "1.0.0", "request-hash", Map.of("changed", true)));

        listener.onMessage(message);
        listener.onMessage(message);

        var keys = ArgumentCaptor.forClass(String.class);
        verify(controlPlane, times(2)).claim(eq(executionId), keys.capture());
        assertThat(keys.getAllValues())
                .allMatch(key -> key.startsWith(message.recordId() + ":claim:"))
                .doesNotHaveDuplicates();
        verify(adapter, times(1)).execute(any(), any(), any(Runnable.class));
    }

    @Test
    void invalidAudienceStopsBeforeAdapterDispatchAndLeavesMessagePending() {
        UUID executionId = UUID.randomUUID();
        var message = new ExecutionMessage("1710000000002-0", UUID.randomUUID(), executionId);
        when(controlPlane.claim(eq(executionId), any(String.class)))
                .thenReturn(new ClaimedExecution(
                        executionId, 1, Instant.now().plusSeconds(30), "wrong-audience-ticket"));
        when(verifier.verify("wrong-audience-ticket", executionId))
                .thenThrow(new InvalidExecutionTicket("Execution ticket audience is invalid"));

        assertThatThrownBy(() -> listener.onMessage(message))
                .isInstanceOf(InvalidExecutionTicket.class);

        verify(adapter, times(0)).execute(any(), any(), any(Runnable.class));
        verifyNoInteractions(acknowledger);
    }

    @Test
    void transientCompletionFailureLeavesMessagePendingForReclaim() {
        UUID executionId = UUID.randomUUID();
        var message = new ExecutionMessage("1710000000003-0", UUID.randomUUID(), executionId);
        var claim = new ClaimedExecution(
                executionId, 1, Instant.now().plusSeconds(30), "signed-ticket");
        when(controlPlane.claim(eq(executionId), any(String.class))).thenReturn(claim);
        when(verifier.verify("signed-ticket", executionId)).thenReturn(authorizedStep(executionId));
        when(adapter.execute(any(), any()))
                .thenReturn(ExecutionStepResult.succeeded(
                        "1.0.0", "request-hash", Map.of("changed", true)));
        org.mockito.Mockito.doThrow(new ControlPlaneClient.TransientControlPlaneException(
                        "Control plane unavailable"))
                .when(controlPlane)
                .complete(any(), any(), any(), any());

        assertThatThrownBy(() -> listener.onMessage(message))
                .isInstanceOf(ControlPlaneClient.TransientControlPlaneException.class);
        verifyNoInteractions(acknowledger);
    }

    @Test
    void staleCompletionLeavesTheReclaimedMessagePending() {
        UUID executionId = UUID.randomUUID();
        var message = new ExecutionMessage("1710000000007-0", UUID.randomUUID(), executionId);
        var claim = new ClaimedExecution(
                executionId, 1, Instant.now().plusSeconds(30), "signed-ticket");
        when(controlPlane.claim(eq(executionId), any(String.class))).thenReturn(claim);
        when(verifier.verify("signed-ticket", executionId)).thenReturn(authorizedStep(executionId));
        when(adapter.execute(any(), any()))
                .thenReturn(ExecutionStepResult.succeeded(
                        "1.0.0", "request-hash", Map.of("changed", true)));
        org.mockito.Mockito.doThrow(new ControlPlaneClient.StaleFencingTokenException(
                        "STALE_FENCING_TOKEN", "Execution lease is stale"))
                .when(controlPlane)
                .complete(any(), any(), any(), any());

        assertThatThrownBy(() -> listener.onMessage(message))
                .isInstanceOf(ControlPlaneClient.StaleFencingTokenException.class);
        verifyNoInteractions(acknowledger);
    }

    @Test
    void heartbeatFailureAfterDispatchLeavesMessagePending() {
        UUID executionId = UUID.randomUUID();
        var message = new ExecutionMessage("1710000000005-0", UUID.randomUUID(), executionId);
        var claim = new ClaimedExecution(
                executionId, 1, Instant.now().plusSeconds(30), "signed-ticket");
        when(controlPlane.claim(eq(executionId), any(String.class))).thenReturn(claim);
        when(verifier.verify("signed-ticket", executionId)).thenReturn(authorizedStep(executionId));
        when(adapter.execute(any(), any()))
                .thenReturn(ExecutionStepResult.succeeded(
                        "1.0.0", "request-hash", Map.of("changed", true)));
        org.mockito.Mockito.doThrow(new ControlPlaneClient.TransientControlPlaneException(
                        "Heartbeat failed"))
                .when(activeLease)
                .ticketForResult();

        assertThatThrownBy(() -> listener.onMessage(message))
                .isInstanceOf(ControlPlaneClient.TransientControlPlaneException.class);

        verify(controlPlane, never()).complete(any(), any(), any(), any());
        verify(activeLease).close();
        verifyNoInteractions(acknowledger);
    }

    @Test
    void expiredTicketBeforeStepPreparationPreventsDispatch() {
        UUID executionId = UUID.randomUUID();
        var message = new ExecutionMessage("1710000000011-0", UUID.randomUUID(), executionId);
        var claim = new ClaimedExecution(
                executionId, 1, Instant.now().plusSeconds(30), "signed-ticket");
        when(controlPlane.claim(eq(executionId), any(String.class))).thenReturn(claim);
        when(verifier.verify("signed-ticket", executionId))
                .thenReturn(authorizedStep(executionId))
                .thenThrow(new InvalidExecutionTicket("Execution ticket expired"));

        assertThatThrownBy(() -> listener.onMessage(message))
                .isInstanceOf(InvalidExecutionTicket.class);

        verify(adapter, never()).execute(any(), any(), any(Runnable.class));
        verify(controlPlane, never()).recordPhase(any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verifyNoInteractions(acknowledger);
    }

    private AuthorizedRunbookStep authorizedStep(UUID executionId) {
        return new AuthorizedRunbookStep(
                executionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "checksum-v1",
                "recover-one",
                "recover_connection_pool",
                "demo-http",
                Map.of("replicas", 1),
                "demo-checkout",
                "R1",
                1);
    }
}
