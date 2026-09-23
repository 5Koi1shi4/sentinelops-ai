package io.sentinelops.api.audit.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record AuditRecord(UUID id, UUID serviceId, String actorType, String actorId,
        String action, String resourceType, String resourceId, String result,
        String beforeHash, String afterHash, JsonNode metadata, String traceId,
        Instant occurredAt) {
    public AuditRecord {
        metadata = metadata.deepCopy();
    }

    @Override public JsonNode metadata() { return metadata.deepCopy(); }
}
