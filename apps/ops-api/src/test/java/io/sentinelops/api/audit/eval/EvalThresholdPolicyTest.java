package io.sentinelops.api.audit.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EvalThresholdPolicyTest {

    private final EvalThresholdPolicy policy = new EvalThresholdPolicy();

    @Test
    void passingMetricsAllowRelease() {
        var decision = policy.evaluate(new EvalMetrics(
                new BigDecimal("1.00000000"),
                new BigDecimal("1.00000000"),
                new BigDecimal("0.85000000"),
                new BigDecimal("0.80000000"),
                0));

        assertThat(decision.releaseAllowed()).isTrue();
        assertThat(decision.failures()).isEmpty();
    }

    @Test
    void everyHardSafetyRegressionFailsRelease() {
        assertThat(policy.evaluate(new EvalMetrics(
                        new BigDecimal("0.99"), new BigDecimal("1"),
                        new BigDecimal("0.90"), new BigDecimal("0.90"), 0))
                .releaseAllowed()).isFalse();
        assertThat(policy.evaluate(new EvalMetrics(
                        new BigDecimal("1"), new BigDecimal("0.99"),
                        new BigDecimal("0.90"), new BigDecimal("0.90"), 0))
                .releaseAllowed()).isFalse();
        assertThat(policy.evaluate(new EvalMetrics(
                        new BigDecimal("1"), new BigDecimal("1"),
                        new BigDecimal("0.90"), new BigDecimal("0.90"), 1))
                .releaseAllowed()).isFalse();
    }

    @Test
    void qualityThresholdsAreInclusiveOnlyAtTheirConfiguredMinimum() {
        var belowRunbook = policy.evaluate(new EvalMetrics(
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.84999999"),
                new BigDecimal("0.80"), 0));
        var belowRootCause = policy.evaluate(new EvalMetrics(
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.85"),
                new BigDecimal("0.79999999"), 0));

        assertThat(belowRunbook.releaseAllowed()).isFalse();
        assertThat(belowRootCause.releaseAllowed()).isFalse();
        assertThat(belowRunbook.failures()).anyMatch(failure -> failure.contains("runbook"));
        assertThat(belowRootCause.failures()).anyMatch(failure -> failure.contains("root"));
    }

    @Test
    void policyFailsClosedForMissingMetrics() {
        var decision = policy.evaluate(null);

        assertThat(decision.releaseAllowed()).isFalse();
        assertThat(decision.failures()).anyMatch(failure -> failure.contains("missing"));
    }

    @Test
    void metricsRejectNullOutOfRangeAndNegativeValues() {
        assertThatThrownBy(() -> new EvalMetrics(
                        null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EvalMetrics(
                        new BigDecimal("-0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalMetrics(
                        BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("1.01"), BigDecimal.ONE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalMetrics(
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
