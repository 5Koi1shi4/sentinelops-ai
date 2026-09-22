package io.sentinelops.api.diagnosis.application.tool;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Server-owned scope passed to every read-only diagnosis tool. */
@org.springframework.modulith.NamedInterface("evaluation")
public record ToolContext(
        UUID incidentId,
        UUID runId,
        UUID serviceId,
        Instant evidenceFrom,
        Instant evidenceTo) {

    public ToolContext {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(evidenceFrom, "evidenceFrom");
        Objects.requireNonNull(evidenceTo, "evidenceTo");
        if (!evidenceFrom.isBefore(evidenceTo)) {
            throw new IllegalArgumentException("evidenceFrom must precede evidenceTo");
        }
    }
}
