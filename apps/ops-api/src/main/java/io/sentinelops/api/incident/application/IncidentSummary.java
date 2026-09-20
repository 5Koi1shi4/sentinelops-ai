package io.sentinelops.api.incident.application;

import io.sentinelops.api.incident.domain.IncidentStatus;
import java.time.Instant;
import java.util.UUID;

public record IncidentSummary(
        UUID id,
        UUID serviceId,
        String serviceKey,
        String title,
        String severity,
        IncidentStatus status,
        long version,
        long occurrenceCount,
        Instant openedAt,
        Instant updatedAt,
        Instant resolvedAt) {}
