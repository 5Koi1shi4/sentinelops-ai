package io.sentinelops.api.execution.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public interface VerificationProbe {

    VerificationResult verify(VerificationSpec specification);

    record VerificationSpec(
            UUID incidentId,
            String probe,
            String targetAlias,
            BigDecimal successThreshold) {

        public VerificationSpec {
            Objects.requireNonNull(incidentId, "incidentId");
            probe = requireText(probe, "probe");
            targetAlias = requireText(targetAlias, "targetAlias");
            Objects.requireNonNull(successThreshold, "successThreshold");
        }
    }

    record VerificationResult(boolean successful, Map<String, Object> sanitizedResult) {

        public VerificationResult {
            sanitizedResult = Map.copyOf(
                    Objects.requireNonNull(sanitizedResult, "sanitizedResult"));
        }

        public static VerificationResult succeeded(Map<String, Object> sanitizedResult) {
            return new VerificationResult(true, sanitizedResult);
        }

        public static VerificationResult failed(Map<String, Object> sanitizedResult) {
            return new VerificationResult(false, sanitizedResult);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
