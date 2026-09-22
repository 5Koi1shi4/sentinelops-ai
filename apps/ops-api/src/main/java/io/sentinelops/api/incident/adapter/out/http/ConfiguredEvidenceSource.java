package io.sentinelops.api.incident.adapter.out.http;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.sentinelops.api.incident.application.evidence.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.util.UriComponentsBuilder;

/** 共用的配置校验和隐私遥测边界；具体来源负责协议参数。 */
public abstract class ConfiguredEvidenceSource implements EvidenceSource {
    private final URI baseUrl;
    private final Map<UUID, Map<String, EvidenceQueryTemplate>> catalog;
    private final int providerCap;
    private final BoundedRestClientFactory client;
    private final ObservationRegistry observations;

    protected ConfiguredEvidenceSource(URI baseUrl, Map<UUID, Map<String, EvidenceQueryTemplate>> catalog,
                                       int providerCap, BoundedRestClientFactory client,
                                       ObservationRegistry observations) {
        this.baseUrl = baseUrl;
        this.catalog = catalog;
        this.providerCap = providerCap;
        this.client = Objects.requireNonNull(client, "client");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    @Override
    public final CapturedEvidence capture(EvidenceQuery query, EvidenceBudget budget) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(budget, "budget");
        if (Duration.between(query.from(), query.to()).compareTo(budget.maxWindow()) > 0) {
            throw new EvidenceBudgetExceeded("evidence window exceeds budget");
        }
        var template = catalog.getOrDefault(query.serviceId(), Map.of()).get(query.queryId());
        if (template == null) throw new IllegalArgumentException("unregistered service query");
        String expression = template.expand(query.parameters());
        var builder = UriComponentsBuilder.fromUriString(baseUrl.toString().replaceAll("/+$", "")).path(endpoint());
        var values = parameters(query, budget, template, expression);
        values.keySet().forEach(key -> builder.queryParam(key, "{" + key + "}"));
        URI target = builder.encode().buildAndExpand(values).toUri();
        // queryId 已从有限注册表解析；不记录 URL、展开的查询或异常链。
        var observation = Observation.createNotStarted("sentinelops.evidence", observations)
                .lowCardinalityKeyValue("source", sourceType())
                .lowCardinalityKeyValue("query", query.queryId()).start();
        try {
            var result = EvidenceResponseNormalizer.normalize(client.get(target, providerCap),
                    sourceType(), query, template, budget);
            observation.lowCardinalityKeyValue("result", "success");
            return result;
        } catch (RuntimeException failure) {
            observation.lowCardinalityKeyValue("result", failure instanceof EvidenceSourceRateLimited
                    ? "rate_limited" : failure instanceof EvidenceBudgetExceeded ? "budget_exceeded" : "error");
            throw failure;
        } finally {
            observation.stop();
        }
    }

    protected abstract String endpoint();
    protected abstract Map<String, String> parameters(EvidenceQuery query, EvidenceBudget budget,
                                                       EvidenceQueryTemplate template, String expression);
}
