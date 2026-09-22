package io.sentinelops.api.incident.application.evidence;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-created query context. The query id is resolved against a source catalog. */
public record EvidenceQuery(
        UUID incidentId,
        UUID serviceId,
        String queryId,
        Map<String, String> parameters,
        Instant from,
        Instant to) {

    public EvidenceQuery {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (queryId.isBlank() || queryId.length() > 128 || !queryId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid query id");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must precede to");
        }
        parameters.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new IllegalArgumentException("invalid query parameter");
            }
        });
        parameters = Map.copyOf(parameters);
    }
}
