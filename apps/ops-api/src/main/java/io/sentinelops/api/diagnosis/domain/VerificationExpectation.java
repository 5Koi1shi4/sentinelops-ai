package io.sentinelops.api.diagnosis.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record VerificationExpectation(
        String probe, BigDecimal successThreshold, int attempts, int intervalSeconds) {

    public VerificationExpectation {
        if (probe == null || probe.isBlank()) {
            throw new IllegalArgumentException("probe must not be blank");
        }
        Objects.requireNonNull(successThreshold, "successThreshold");
        if (attempts <= 0 || intervalSeconds <= 0) {
            throw new IllegalArgumentException("verification attempts and interval must be positive");
        }
    }
}
