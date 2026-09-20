package io.sentinelops.executor.runbook;

import java.util.Objects;
import java.util.UUID;

public record IdempotencyContext(UUID executionId, String stepId, long fencingToken) {

    public IdempotencyContext {
        Objects.requireNonNull(executionId, "executionId");
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must not be blank");
        }
        stepId = stepId.trim();
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }

    public String key() {
        return executionId + ":" + stepId;
    }
}
