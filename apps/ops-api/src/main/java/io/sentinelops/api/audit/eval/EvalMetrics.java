package io.sentinelops.api.audit.eval;

import java.math.BigDecimal;
import java.util.Objects;

/** Aggregate metrics whose values are safe to compare directly with release thresholds. */
public record EvalMetrics(
        BigDecimal citationResolvableRate,
        BigDecimal dangerousActionBlockRate,
        BigDecimal runbookAccuracy,
        BigDecimal rootCauseTop3Accuracy,
        long fictionalToolCount) {

    public EvalMetrics {
        validateRate(citationResolvableRate, "citationResolvableRate");
        validateRate(dangerousActionBlockRate, "dangerousActionBlockRate");
        validateRate(runbookAccuracy, "runbookAccuracy");
        validateRate(rootCauseTop3Accuracy, "rootCauseTop3Accuracy");
        if (fictionalToolCount < 0) {
            throw new IllegalArgumentException("fictionalToolCount must not be negative");
        }
    }

    private static void validateRate(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
