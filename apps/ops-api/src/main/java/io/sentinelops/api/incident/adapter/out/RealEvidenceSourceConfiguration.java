package io.sentinelops.api.incident.adapter.out;

import io.micrometer.observation.ObservationRegistry;
import io.sentinelops.api.incident.adapter.out.http.BoundedRestClientFactory;
import io.sentinelops.api.incident.adapter.out.loki.LokiEvidenceSource;
import io.sentinelops.api.incident.adapter.out.loki.LokiProperties;
import io.sentinelops.api.incident.adapter.out.prometheus.PrometheusEvidenceSource;
import io.sentinelops.api.incident.adapter.out.prometheus.PrometheusProperties;
import io.sentinelops.api.incident.application.evidence.EvidenceQueryTemplate;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sentinelops.evidence.mode", havingValue = "real")
class RealEvidenceSourceConfiguration {
    private static final UUID CHECKOUT_SERVICE =
            UUID.fromString("0199a000-0000-7000-8000-000000000001");
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    @Bean
    BoundedRestClientFactory boundedEvidenceClient() {
        return new BoundedRestClientFactory();
    }

    @Bean
    PrometheusEvidenceSource prometheusEvidenceSource(
            @Value("${sentinelops.evidence.prometheus.base-url}") String baseUrl,
            BoundedRestClientFactory client, ObservationRegistry observations) {
        var templates = Map.of(
                "checkout_error_rate", prom("sum(rate(demo_checkout_errors_total[2m]))"),
                "checkout_latency_mean", prom("sum(rate(demo_checkout_latency_seconds_sum[2m]))"
                        + " / clamp_min(sum(rate(demo_checkout_latency_seconds_count[2m])), 0.000001)"),
                "pool_pending", prom("demo_pool_pending"));
        return new PrometheusEvidenceSource(new PrometheusProperties(
                URI.create(baseUrl), Map.of(CHECKOUT_SERVICE, templates), MAX_RESPONSE_BYTES),
                client, observations);
    }

    @Bean
    LokiEvidenceSource lokiEvidenceSource(
            @Value("${sentinelops.evidence.loki.base-url}") String baseUrl,
            BoundedRestClientFactory client, ObservationRegistry observations) {
        var templates = Map.of("acquire_timeout_logs", new EvidenceQueryTemplate(
                "{service_name=\"sentinelops-demo-service\"} |= \"Demo checkout acquire timeout\"",
                Map.of(), Set.of("service_name", "level"), Duration.ofSeconds(1)));
        return new LokiEvidenceSource(new LokiProperties(
                URI.create(baseUrl), Map.of(CHECKOUT_SERVICE, templates), MAX_RESPONSE_BYTES),
                client, observations);
    }

    private static EvidenceQueryTemplate prom(String expression) {
        return new EvidenceQueryTemplate(expression, Map.of(),
                Set.of("__name__", "job", "service_key"), Duration.ofSeconds(5));
    }
}
