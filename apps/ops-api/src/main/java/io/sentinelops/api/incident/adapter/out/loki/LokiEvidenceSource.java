package io.sentinelops.api.incident.adapter.out.loki;

import io.micrometer.observation.ObservationRegistry;
import io.sentinelops.api.incident.adapter.out.http.BoundedRestClientFactory;
import io.sentinelops.api.incident.adapter.out.http.ConfiguredEvidenceSource;
import io.sentinelops.api.incident.application.evidence.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;

public final class LokiEvidenceSource extends ConfiguredEvidenceSource {
    public LokiEvidenceSource(LokiProperties properties, BoundedRestClientFactory client,
                              ObservationRegistry observations) {
        super(properties.baseUrl(), properties.queryTemplates(), properties.maxResponseBytes(), client, observations);
    }

    @Override public String sourceType() { return "loki"; }
    @Override protected String endpoint() { return "/loki/api/v1/query_range"; }
    @Override protected Map<String, String> parameters(EvidenceQuery query, EvidenceBudget budget,
                                                       EvidenceQueryTemplate template, String expression) {
        return Map.of("query", expression, "start", nanos(query.from()), "end", nanos(query.to()),
                "direction", "backward", "limit", Integer.toString(budget.maxItems()));
    }

    private static String nanos(Instant time) {
        return BigInteger.valueOf(time.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000))
                .add(BigInteger.valueOf(time.getNano())).toString();
    }
}
