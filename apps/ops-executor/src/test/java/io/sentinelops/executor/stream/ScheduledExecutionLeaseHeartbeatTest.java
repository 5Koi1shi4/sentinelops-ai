package io.sentinelops.executor.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.sentinelops.executor.controlplane.ControlPlaneClient;
import io.sentinelops.executor.controlplane.ControlPlaneClient.ClaimedExecution;
import io.sentinelops.executor.controlplane.ControlPlaneClient.HeartbeatLease;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduledExecutionLeaseHeartbeatTest {

    @Test
    void renewsTheLeasePeriodicallyWithUniqueIdempotencyKeys() throws Exception {
        var controlPlane = mock(ControlPlaneClient.class);
        var pulses = new CountDownLatch(2);
        var pulseCount = new AtomicInteger();
        doAnswer(invocation -> {
                    int pulse = pulseCount.incrementAndGet();
                    pulses.countDown();
                    return new HeartbeatLease(
                            invocation.getArgument(0),
                            3,
                            Instant.now().plusSeconds(30),
                            "rotated-ticket-" + pulse);
                })
                .when(controlPlane)
                .heartbeat(any(UUID.class), anyString(), eq(3L), anyString());
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var heartbeats = new ScheduledExecutionLeaseHeartbeat(
                controlPlane, Duration.ofMillis(5), scheduler);
        UUID executionId = UUID.randomUUID();
        var message = new ExecutionMessage("1710000000006-0", UUID.randomUUID(), executionId);
        var claim = new ClaimedExecution(
                executionId, 3, Instant.now().plusSeconds(30), "signed-ticket");

        String resultTicket;
        try (var activeLease = heartbeats.start(message, claim, "delivery-attempt")) {
            assertThat(pulses.await(1, TimeUnit.SECONDS)).isTrue();
            resultTicket = activeLease.ticketForResult();
        } finally {
            heartbeats.shutdown();
        }

        var keys = ArgumentCaptor.forClass(String.class);
        var tickets = ArgumentCaptor.forClass(String.class);
        verify(controlPlane, atLeast(2))
                .heartbeat(eq(executionId), tickets.capture(), eq(3L), keys.capture());
        assertThat(tickets.getAllValues().getFirst()).isEqualTo("signed-ticket");
        for (int index = 1; index < tickets.getAllValues().size(); index++) {
            assertThat(tickets.getAllValues().get(index))
                    .isEqualTo("rotated-ticket-" + index);
        }
        assertThat(resultTicket).isEqualTo("rotated-ticket-" + pulseCount.get());
        assertThat(keys.getAllValues())
                .allMatch(key -> key.startsWith(
                        message.recordId() + ":heartbeat:delivery-attempt:"))
                .doesNotHaveDuplicates();
    }

    @Test
    void anAlreadyQueuedPulseCannotRotateTheTicketAfterFinishingStarts() {
        var controlPlane = mock(ControlPlaneClient.class);
        UUID executionId = UUID.randomUUID();
        when(controlPlane.heartbeat(
                        eq(executionId), eq("signed-ticket"), eq(3L), anyString()))
                .thenReturn(new HeartbeatLease(
                        executionId,
                        3,
                        Instant.now().plusSeconds(30),
                        "rotated-ticket"));
        var scheduler = new CapturingScheduler();
        var heartbeats = new ScheduledExecutionLeaseHeartbeat(
                controlPlane, Duration.ofSeconds(10), scheduler);
        var message = new ExecutionMessage(
                "1710000000007-0", UUID.randomUUID(), executionId);
        var claim = new ClaimedExecution(
                executionId, 3, Instant.now().plusSeconds(30), "signed-ticket");

        try (var activeLease = heartbeats.start(message, claim, "delivery-attempt")) {
            assertThat(activeLease.ticketForResult()).isEqualTo("rotated-ticket");
            scheduler.runCapturedTaskDespiteCancellation();
        } finally {
            heartbeats.shutdown();
        }

        verify(controlPlane, times(1))
                .heartbeat(eq(executionId), anyString(), eq(3L), anyString());
    }

    @Test
    void finishingWaitsForAnInFlightHeartbeatAndUsesItsRotatedTicket() throws Exception {
        var controlPlane = mock(ControlPlaneClient.class);
        UUID executionId = UUID.randomUUID();
        var heartbeatEntered = new CountDownLatch(1);
        var releaseHeartbeat = new CountDownLatch(1);
        var heartbeatCount = new AtomicInteger();
        doAnswer(invocation -> {
                    int pulse = heartbeatCount.incrementAndGet();
                    if (pulse == 2) {
                        heartbeatEntered.countDown();
                        assertThat(releaseHeartbeat.await(1, TimeUnit.SECONDS)).isTrue();
                    }
                    return new HeartbeatLease(
                            executionId,
                            3,
                            Instant.now().plusSeconds(30),
                            "rotated-ticket-" + pulse);
                })
                .when(controlPlane)
                .heartbeat(eq(executionId), anyString(), eq(3L), anyString());
        var scheduler = new CapturingScheduler();
        var heartbeats = new ScheduledExecutionLeaseHeartbeat(
                controlPlane, Duration.ofSeconds(10), scheduler);
        var message = new ExecutionMessage(
                "1710000000008-0", UUID.randomUUID(), executionId);
        var claim = new ClaimedExecution(
                executionId, 3, Instant.now().plusSeconds(30), "signed-ticket");

        try (var activeLease = heartbeats.start(message, claim, "delivery-attempt");
                var workers = Executors.newFixedThreadPool(2)) {
            var heartbeat = workers.submit(scheduler::runCapturedTaskDespiteCancellation);
            assertThat(heartbeatEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var finishing = workers.submit(activeLease::ticketForResult);
            assertThat(finishing.isDone()).isFalse();

            releaseHeartbeat.countDown();

            heartbeat.get(1, TimeUnit.SECONDS);
            assertThat(finishing.get(1, TimeUnit.SECONDS))
                    .isEqualTo("rotated-ticket-2");
            scheduler.runCapturedTaskDespiteCancellation();
        } finally {
            releaseHeartbeat.countDown();
            heartbeats.shutdown();
        }

        verify(controlPlane, times(2))
                .heartbeat(eq(executionId), anyString(), eq(3L), anyString());
    }

    private static final class CapturingScheduler extends ScheduledThreadPoolExecutor {

        private Runnable capturedTask;

        private CapturingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command,
                long initialDelay,
                long period,
                TimeUnit unit) {
            capturedTask = command;
            return mock(ScheduledFuture.class);
        }

        private void runCapturedTaskDespiteCancellation() {
            capturedTask.run();
        }
    }

}
