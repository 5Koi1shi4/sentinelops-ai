package io.sentinelops.api.incident.application.evidence;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record EvidenceRequest(String sourceType, String queryId, Map<String, String> parameters) {
    public EvidenceRequest {
        if (!Set.of("prometheus", "loki").contains(Objects.requireNonNull(sourceType, "sourceType"))) {
            throw new IllegalArgumentException("unknown evidence source");
        }
        if (queryId == null || queryId.length() > 128 || !queryId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid registered query ID");
        }
        parameters = Map.copyOf(parameters);
        if (parameters.size() > 16 || parameters.entrySet().stream().anyMatch(entry ->
                !entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}") || entry.getValue().length() > 128)) {
            throw new IllegalArgumentException("invalid evidence parameters");
        }
    }
}
