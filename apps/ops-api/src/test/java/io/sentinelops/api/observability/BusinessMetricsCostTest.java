package io.sentinelops.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.sentinelops.api.shared.observability.BusinessMetrics;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BusinessMetricsCostTest {

    @Test
    void costRequiresTrustedProviderModelAndBothTokenRates() {
        var meters = new SimpleMeterRegistry();
        var metrics = new BusinessMetrics(ObservationRegistry.NOOP, meters,
                OpenTelemetry.noop(), "openai-compatible", "trusted-model",
                new BigDecimal("2.50"), new BigDecimal("10.00"));

        metrics.aiTokens("openai-compatible", "different-model", 1_000_000, 1_000_000);
        assertThat(meters.find("sentinelops.ai.cost.estimated").counter()).isNull();

        metrics.aiTokens("openai-compatible", "trusted-model", 1_000_000, 1_000_000);
        assertThat(meters.get("sentinelops.ai.cost.estimated")
                .tag("provider", "openai-compatible")
                .tag("model", "trusted-model").counter().count()).isEqualTo(12.5);
    }

    @Test
    void missingPricingLeavesCostUnknown() {
        var meters = new SimpleMeterRegistry();
        var metrics = new BusinessMetrics(ObservationRegistry.NOOP, meters,
                OpenTelemetry.noop());

        metrics.aiTokens("openai-compatible", "trusted-model", 100, 100);

        assertThat(meters.find("sentinelops.ai.cost.estimated").counter()).isNull();
    }
}
