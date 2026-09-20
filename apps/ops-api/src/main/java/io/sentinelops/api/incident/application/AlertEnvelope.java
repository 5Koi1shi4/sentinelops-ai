package io.sentinelops.api.incident.application;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record AlertEnvelope(
        String source,
        String sourceEventId,
        String serviceKey,
        String fingerprint,
        String title,
        String severity,
        AlertStatus status,
        JsonNode payload) {

    private static final Set<String> ALLOWED_SEVERITIES = Set.of("sev1", "sev2", "sev3", "sev4");

    public AlertEnvelope {
        source = requireText(source, "source");
        sourceEventId = normalizeOptional(sourceEventId);
        serviceKey = requireText(serviceKey, "serviceKey");
        fingerprint = requireText(fingerprint, "fingerprint").toLowerCase(Locale.ROOT);
        title = requireText(title, "title");
        severity = requireText(severity, "severity").toLowerCase(Locale.ROOT);
        if (!ALLOWED_SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("severity must be one of sev1, sev2, sev3, sev4");
        }
        status = Objects.requireNonNull(status, "status");
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum AlertStatus {
        FIRING,
        RESOLVED
    }
}
