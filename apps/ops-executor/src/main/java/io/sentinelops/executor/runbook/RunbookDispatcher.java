package io.sentinelops.executor.runbook;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RunbookDispatcher {

    private final Map<String, RunbookAdapter> adapters;
    private final ObservationRegistry observations;

    @Autowired
    public RunbookDispatcher(List<RunbookAdapter> adapters,
            ObservationRegistry observations) {
        var indexed = new LinkedHashMap<String, RunbookAdapter>();
        for (var adapter : adapters) {
            String adapterId = adapter.adapterId();
            if (adapterId == null || adapterId.isBlank()) {
                throw new IllegalArgumentException("Runbook adapter ID must not be blank");
            }
            if (indexed.put(adapterId, adapter) != null) {
                throw new IllegalStateException("Duplicate Runbook adapter: " + adapterId);
            }
        }
        this.adapters = Map.copyOf(indexed);
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    public RunbookDispatcher(List<RunbookAdapter> adapters) {
        this(adapters, ObservationRegistry.NOOP);
    }

    public ExecutionStepResult dispatch(
            AuthorizedRunbookStep step, IdempotencyContext context) {
        return dispatch(step, context, () -> {});
    }

    public ExecutionStepResult dispatch(
            AuthorizedRunbookStep step, IdempotencyContext context, Runnable beforeTransport) {
        var adapter = adapters.get(step.adapterId());
        if (adapter == null) {
            throw new UnsupportedRunbookStepException(
                    "Unknown Runbook adapter: " + step.adapterId());
        }
        if (!adapter.supportedOperations().contains(step.operation())) {
            throw new UnsupportedRunbookStepException(
                    "Unsupported Runbook operation for adapter " + step.adapterId());
        }
        var observation = Observation.createNotStarted(
                "sentinelops.executor.adapter", observations)
                .lowCardinalityKeyValue("adapter", adapter.adapterId())
                .highCardinalityKeyValue("execution.id", step.executionId().toString())
                .start();
        try (var scope = observation.openScope()) {
            var result = adapter.execute(step, context, beforeTransport);
            observation.lowCardinalityKeyValue("result", result.succeeded() ? "success" : "failure");
            return result;
        } catch (RuntimeException failure) {
            observation.lowCardinalityKeyValue("result", "error");
            throw failure;
        } finally {
            observation.stop();
        }
    }
}
