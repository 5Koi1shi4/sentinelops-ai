package io.sentinelops.api.diagnosis.application.tool;

import io.sentinelops.api.incident.application.evidence.EvidenceSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Redacted evidence envelope returned by a read-only diagnosis tool. */
public record ToolResult(
        UUID evidenceId,
        String sourceType,
        String queryId,
        Instant capturedAt,
        String contentHash,
        boolean truncated,
        int redactionCount,
        Set<String> appliedRules,
        TrustLevel trust,
        JsonNode payload) {

    public ToolResult {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(appliedRules, "appliedRules");
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(payload, "payload");
        if (contentHash == null || !contentHash.matches("[a-f0-9]{64}")
                || redactionCount < 0) {
            throw new IllegalArgumentException("invalid tool result metadata");
        }
        appliedRules = Set.copyOf(appliedRules);
        payload = payload.deepCopy();
    }

    public static ToolResult fromSnapshot(EvidenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new ToolResult(
                snapshot.id(),
                snapshot.sourceType(),
                snapshot.queryId(),
                snapshot.capturedAt(),
                snapshot.contentHash(),
                snapshot.truncated(),
                snapshot.redactionCount(),
                snapshot.appliedRules(),
                TrustLevel.UNTRUSTED_EXTERNAL_DATA,
                snapshot.payload());
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
