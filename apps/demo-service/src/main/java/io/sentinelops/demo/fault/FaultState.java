package io.sentinelops.demo.fault;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public final class FaultState {

    private final AtomicReference<FaultMode> mode =
            new AtomicReference<>(FaultMode.NONE);
    private final Map<String, RecoveryRecord> recoveries = new HashMap<>();
    private final Counter recoverySideEffects;

    public FaultState(MeterRegistry meters) {
        Gauge.builder("demo_fault_active", mode, current ->
                        current.get() == FaultMode.NONE ? 0.0 : 1.0)
                .description("Whether the controlled Demo fault is active")
                .register(meters);
        Gauge.builder("demo_pool_pending", mode, current ->
                        current.get() == FaultMode.CONNECTION_POOL_EXHAUSTED ? 12.0 : 0.0)
                .description("Controlled pending connection acquisitions in the Demo fault")
                .register(meters);
        recoverySideEffects = Counter.builder("demo.recovery.side.effect")
                .description("Number of recovery actions that changed Demo service state")
                .register(meters);
    }

    public FaultMode mode() {
        return mode.get();
    }

    public synchronized boolean enable(FaultMode requested) {
        Objects.requireNonNull(requested, "requested");
        return mode.getAndSet(requested) != requested;
    }

    public synchronized boolean clear() {
        return mode.getAndSet(FaultMode.NONE) != FaultMode.NONE;
    }

    public synchronized RecoveryResult recover(String idempotencyKey, long fencingToken) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        String key = idempotencyKey.trim();
        var existing = recoveries.get(key);
        if (existing != null) {
            if (fencingToken < existing.highestFencingToken()) {
                throw new StaleRecoveryFenceException();
            }
            recoveries.put(key, new RecoveryRecord(fencingToken, existing.changed()));
            return new RecoveryResult(existing.changed(), fencingToken, true);
        }

        boolean changed = mode.getAndSet(FaultMode.NONE) != FaultMode.NONE;
        if (changed) {
            recoverySideEffects.increment();
        }
        recoveries.put(key, new RecoveryRecord(fencingToken, changed));
        return new RecoveryResult(changed, fencingToken, false);
    }

    public boolean active() {
        return mode() != FaultMode.NONE;
    }

    public record RecoveryResult(boolean changed, long fencingToken, boolean replayed) {}

    private record RecoveryRecord(long highestFencingToken, boolean changed) {}

    public static final class StaleRecoveryFenceException extends RuntimeException {

        public StaleRecoveryFenceException() {
            super("The recovery fencing token is stale for this idempotency key");
        }
    }
}
