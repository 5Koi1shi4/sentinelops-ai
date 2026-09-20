package io.sentinelops.api.knowledge.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record RunbookVersion(
        UUID id,
        UUID runbookId,
        String runbookKey,
        UUID serviceId,
        int versionNumber,
        Lifecycle lifecycle,
        RiskLevel riskLevel,
        String adapterId,
        JsonNode definition,
        String definitionChecksum,
        Instant publishedAt) {

    public RunbookVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runbookId, "runbookId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(riskLevel, "riskLevel");
        if (lifecycle == Lifecycle.DRAFT && publishedAt != null) {
            throw new IllegalArgumentException("draft Runbook versions cannot be published");
        }
        if (lifecycle != Lifecycle.DRAFT && publishedAt == null) {
            throw new IllegalArgumentException("published Runbook versions require publishedAt");
        }
        if (runbookKey == null || runbookKey.isBlank()) {
            throw new IllegalArgumentException("runbookKey must not be blank");
        }
        if (versionNumber <= 0) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        definition = Objects.requireNonNull(definition, "definition").deepCopy();
        if (definitionChecksum == null || definitionChecksum.isBlank()) {
            throw new IllegalArgumentException("definitionChecksum must not be blank");
        }
    }

    @Override
    public JsonNode definition() {
        return definition.deepCopy();
    }

    public enum Lifecycle {
        DRAFT,
        PUBLISHED,
        RETIRED
    }
}
