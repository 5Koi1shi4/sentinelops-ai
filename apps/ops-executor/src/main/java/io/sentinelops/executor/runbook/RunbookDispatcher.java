package io.sentinelops.executor.runbook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RunbookDispatcher {

    private final Map<String, RunbookAdapter> adapters;

    public RunbookDispatcher(List<RunbookAdapter> adapters) {
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
    }

    public ExecutionStepResult dispatch(
            AuthorizedRunbookStep step, IdempotencyContext context) {
        var adapter = adapters.get(step.adapterId());
        if (adapter == null) {
            throw new UnsupportedRunbookStepException(
                    "Unknown Runbook adapter: " + step.adapterId());
        }
        if (!adapter.supportedOperations().contains(step.operation())) {
            throw new UnsupportedRunbookStepException(
                    "Unsupported Runbook operation for adapter " + step.adapterId());
        }
        return adapter.execute(step, context);
    }
}
