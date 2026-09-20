package io.sentinelops.executor.runbook;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AuthorizedRunbookStep(
        UUID executionId,
        UUID incidentId,
        UUID runbookVersionId,
        String runbookChecksum,
        String stepId,
        String operation,
        String adapterId,
        Map<String, Object> parameters,
        String target,
        String risk,
        long fencingToken) {

    public AuthorizedRunbookStep {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(runbookVersionId, "runbookVersionId");
        runbookChecksum = requireText(runbookChecksum, "runbookChecksum");
        stepId = requireText(stepId, "stepId");
        operation = requireText(operation, "operation");
        adapterId = requireText(adapterId, "adapterId");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        target = requireText(target, "target");
        risk = requireText(risk, "risk");
        if (!java.util.Set.of("R0", "R1", "R2", "R3").contains(risk)) {
            throw new IllegalArgumentException("risk must be a known level");
        }
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
