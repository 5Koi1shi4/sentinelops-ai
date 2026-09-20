package io.sentinelops.executor.stream;

import io.sentinelops.executor.controlplane.ControlPlaneClient;
import io.sentinelops.executor.controlplane.ControlPlaneClient.ClaimedExecution;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ScheduledExecutionLeaseHeartbeat implements ExecutionLeaseHeartbeat {

    private static final Duration EXECUTION_LEASE_DURATION = Duration.ofSeconds(30);

    private final ControlPlaneClient controlPlane;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;

    @Autowired
    public ScheduledExecutionLeaseHeartbeat(
            ControlPlaneClient controlPlane,
            @Value("${sentinelops.executor.heartbeat-interval:PT10S}") Duration interval) {
        this(controlPlane, interval, newScheduler());
    }

    ScheduledExecutionLeaseHeartbeat(
            ControlPlaneClient controlPlane,
            Duration interval,
            ScheduledExecutorService scheduler) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (interval.isNegative()
                || interval.isZero()
                || interval.toMillis() == 0
                || interval.compareTo(EXECUTION_LEASE_DURATION) >= 0) {
            throw new IllegalArgumentException(
                    "heartbeat interval must be between one millisecond and the lease duration");
        }
    }

    @Override
    public ActiveLease start(
            ExecutionMessage message, ClaimedExecution claim, String attemptId) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(claim, "claim");
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("attemptId must not be blank");
        }
        var failure = new AtomicReference<RuntimeException>();
        var currentTicket = new AtomicReference<>(claim.ticket());
        var sequence = new AtomicLong();
        var pulseLock = new ReentrantLock();
        var finishing = new AtomicBoolean();
        pulse(
                message,
                claim,
                attemptId,
                sequence,
                failure,
                currentTicket,
                finishing,
                pulseLock);
        RuntimeException initialFailure = failure.get();
        if (initialFailure != null) {
            throw initialFailure;
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> pulse(
                        message,
                        claim,
                        attemptId,
                        sequence,
                        failure,
                        currentTicket,
                        finishing,
                        pulseLock),
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
        return new ScheduledActiveLease(
                future, failure, currentTicket, finishing, pulseLock);
    }

    private void pulse(
            ExecutionMessage message,
            ClaimedExecution claim,
            String attemptId,
            AtomicLong sequence,
            AtomicReference<RuntimeException> failure,
            AtomicReference<String> currentTicket,
            AtomicBoolean finishing,
            ReentrantLock pulseLock) {
        if (failure.get() != null || finishing.get()) {
            return;
        }
        pulseLock.lock();
        try {
            if (failure.get() != null || finishing.get()) {
                return;
            }
            var renewed = controlPlane.heartbeat(
                    message.executionId(),
                    currentTicket.get(),
                    claim.fencingToken(),
                    message.recordId() + ":heartbeat:" + attemptId + ':'
                            + sequence.incrementAndGet());
            if (!renewed.executionId().equals(message.executionId())
                    || renewed.fencingToken() != claim.fencingToken()) {
                throw new ControlPlaneClient.ControlPlaneProtocolException(
                        "Heartbeat response does not match the active execution lease");
            }
            currentTicket.set(renewed.ticket());
        } catch (RuntimeException heartbeatFailure) {
            failure.compareAndSet(null, heartbeatFailure);
        } finally {
            pulseLock.unlock();
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static ScheduledThreadPoolExecutor newScheduler() {
        var executor = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon()
                        .name("sentinelops-execution-heartbeat-", 0)
                        .factory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private record ScheduledActiveLease(
            ScheduledFuture<?> future,
            AtomicReference<RuntimeException> failure,
            AtomicReference<String> currentTicket,
            AtomicBoolean finishing,
            ReentrantLock pulseLock)
            implements ActiveLease {

        @Override
        public String ticketForResult() {
            pulseLock.lock();
            try {
                finishing.set(true);
                future.cancel(false);
                RuntimeException heartbeatFailure = failure.get();
                if (heartbeatFailure != null) {
                    throw heartbeatFailure;
                }
                return currentTicket.get();
            } finally {
                pulseLock.unlock();
            }
        }

        @Override
        public void close() {
            pulseLock.lock();
            try {
                finishing.set(true);
                future.cancel(false);
            } finally {
                pulseLock.unlock();
            }
        }
    }
}
