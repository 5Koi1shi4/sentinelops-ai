package io.sentinelops.api.incident.adapter.out.loki;

import io.sentinelops.api.incident.application.evidence.EvidenceQueryTemplate;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record LokiProperties(
        URI baseUrl,
        Map<UUID, Map<String, EvidenceQueryTemplate>> queryTemplates,
        int maxResponseBytes) {

    public LokiProperties {
        validateBaseUrl(baseUrl);
        if (maxResponseBytes < 256 || maxResponseBytes > 1_048_576) {
            throw new IllegalArgumentException("invalid Loki response cap");
        }
        Objects.requireNonNull(queryTemplates, "queryTemplates");
        var copy = new java.util.HashMap<UUID, Map<String, EvidenceQueryTemplate>>();
        queryTemplates.forEach((serviceId, templates) -> {
            if (serviceId == null || templates == null || templates.isEmpty()) {
                throw new IllegalArgumentException("invalid Loki service catalog");
            }
            templates.forEach((queryId, template) -> {
                if (queryId == null || !queryId.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || template == null) {
                    throw new IllegalArgumentException("invalid Loki query catalog");
                }
            });
            copy.put(serviceId, Map.copyOf(templates));
        });
        queryTemplates = Map.copyOf(copy);
    }

    private static void validateBaseUrl(URI baseUrl) {
        if (baseUrl == null || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || (!"http".equalsIgnoreCase(baseUrl.getScheme()) && !"https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("Loki base URL must be an explicit http(s) origin");
        }
    }
}
