package io.sentinelops.api.incident.application;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record IncidentTimelineItem(
        UUID id,
        long sequence,
        String eventType,
        String actorType,
        String actorId,
        String source,
        String sourceEventId,
        JsonNode payload,
        Instant occurredAt) {}
