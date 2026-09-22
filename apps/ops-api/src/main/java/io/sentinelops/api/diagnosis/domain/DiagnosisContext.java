package io.sentinelops.api.diagnosis.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

public record DiagnosisContext(
        UUID incidentId,
        long incidentVersion,
        UUID serviceId,
        List<DiagnosisEvidence> evidence,
        UUID runId,
        Instant evidenceFrom,
        Instant evidenceTo,
        String runbookCorpusVersion) {

    /**
     * Stage 1 compatibility constructor. A model-backed engine must receive the
     * server scope constructor below so the provider cannot invent a run or time
     * window.
     */
    public DiagnosisContext(
            UUID incidentId, long incidentVersion, UUID serviceId, List<DiagnosisEvidence> evidence) {
        this(incidentId, incidentVersion, serviceId, evidence, null, null, null, "unknown");
    }

    public DiagnosisContext {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(serviceId, "serviceId");
        if (incidentVersion < 0) {
            throw new IllegalArgumentException("incidentVersion must not be negative");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        runbookCorpusVersion = requireText(runbookCorpusVersion, "runbookCorpusVersion");
        if ((evidenceFrom == null) != (evidenceTo == null)) {
            throw new IllegalArgumentException("evidenceFrom and evidenceTo must be provided together");
        }
        if (evidenceFrom != null && !evidenceFrom.isBefore(evidenceTo)) {
            throw new IllegalArgumentException("evidenceFrom must precede evidenceTo");
        }
        if (runId == null && evidenceFrom != null) {
            throw new IllegalArgumentException("runId is required with server evidence scope");
        }
    }

    public boolean hasServerScope() {
        return runId != null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
