package io.sentinelops.api.diagnosis.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DiagnosisContext(
        UUID incidentId, long incidentVersion, UUID serviceId, List<DiagnosisEvidence> evidence) {

    public DiagnosisContext {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(serviceId, "serviceId");
        if (incidentVersion < 0) {
            throw new IllegalArgumentException("incidentVersion must not be negative");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
}
