package io.sentinelops.api.incident.application.evidence;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Immutable application view of a frozen, redacted evidence snapshot. */
public record EvidenceSnapshot(UUID id, String sourceType, String queryId, Instant capturedAt,
                               String contentHash, boolean truncated, int redactionCount,
                               Set<String> appliedRules, JsonNode payload) {
    public EvidenceSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        if (contentHash == null || !contentHash.matches("[a-f0-9]{64}") || redactionCount < 0) {
            throw new IllegalArgumentException("invalid evidence snapshot metadata");
        }
        appliedRules = Set.copyOf(appliedRules);
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
    }
    @Override public JsonNode payload() { return payload.deepCopy(); }
}
