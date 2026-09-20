package io.sentinelops.api.diagnosis.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record DiagnosisEvidence(
        UUID id,
        String sourceType,
        String sourceRef,
        JsonNode redactedPayload,
        String contentHash,
        Instant capturedAt,
        boolean truncated) {

    public DiagnosisEvidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capturedAt, "capturedAt");
        redactedPayload = Objects.requireNonNull(redactedPayload, "redactedPayload").deepCopy();
        sourceType = requireText(sourceType, "sourceType");
        sourceRef = requireText(sourceRef, "sourceRef");
        contentHash = requireText(contentHash, "contentHash");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
