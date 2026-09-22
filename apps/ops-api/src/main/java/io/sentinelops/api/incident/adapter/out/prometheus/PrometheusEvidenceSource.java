package io.sentinelops.api.incident.adapter.out.prometheus;

import io.micrometer.observation.ObservationRegistry;
import io.sentinelops.api.incident.adapter.out.http.BoundedRestClientFactory;
import io.sentinelops.api.incident.adapter.out.http.ConfiguredEvidenceSource;
import io.sentinelops.api.incident.application.evidence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public final class PrometheusEvidenceSource extends ConfiguredEvidenceSource {
    public PrometheusEvidenceSource(PrometheusProperties properties, BoundedRestClientFactory client,
                                    ObservationRegistry observations) {
        super(properties.baseUrl(), properties.queryTemplates(), properties.maxResponseBytes(), client, observations);
    }

    @Override public String sourceType() { return "prometheus"; }
    @Override protected String endpoint() { return "/api/v1/query_range"; }
    @Override protected Map<String, String> parameters(EvidenceQuery query, EvidenceBudget budget,
                                                       EvidenceQueryTemplate template, String expression) {
        var window = BigDecimal.valueOf(Duration.between(query.from(), query.to()).toNanos(), 9);
        var step = window.divide(BigDecimal.valueOf(Math.max(1, budget.maxItems() - 1)), 0, RoundingMode.CEILING)
                .max(BigDecimal.valueOf(template.minimumStep().toNanos(), 9));
        return Map.of("query", expression, "start", timestamp(query.from()), "end", timestamp(query.to()),
                "step", step.stripTrailingZeros().toPlainString(), "timeout", "8s",
                "limit", Integer.toString(budget.maxItems()));
    }

    private static String timestamp(Instant time) {
        return BigDecimal.valueOf(time.getEpochSecond()).add(BigDecimal.valueOf(time.getNano(), 9))
                .stripTrailingZeros().toPlainString();
    }
}
