package io.sentinelops.api.incident.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IncidentTimelineItem(
        UUID id,
        long sequence,
        String eventType,
        String actorType,
        String actorId,
        String source,
        String sourceEventId,
        String summary,
        String traceId,
        List<UUID> evidenceIds,
        Instant occurredAt) {

    public IncidentTimelineItem {
        evidenceIds = List.copyOf(evidenceIds);
    }
}
