package io.sentinelops.executor.runbook;

import java.util.Map;
import java.util.Objects;

public record ExecutionStepResult(
        boolean succeeded,
        String adapterVersion,
        String requestHash,
        Map<String, Object> sanitizedResult) {

    public ExecutionStepResult {
        adapterVersion = requireText(adapterVersion, "adapterVersion");
        requestHash = requireText(requestHash, "requestHash");
        sanitizedResult = Map.copyOf(
                Objects.requireNonNull(sanitizedResult, "sanitizedResult"));
    }

    public static ExecutionStepResult succeeded(
            String adapterVersion, String requestHash, Map<String, Object> sanitizedResult) {
        return new ExecutionStepResult(true, adapterVersion, requestHash, sanitizedResult);
    }

    public static ExecutionStepResult failed(
            String adapterVersion, String requestHash, Map<String, Object> sanitizedResult) {
        return new ExecutionStepResult(false, adapterVersion, requestHash, sanitizedResult);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
