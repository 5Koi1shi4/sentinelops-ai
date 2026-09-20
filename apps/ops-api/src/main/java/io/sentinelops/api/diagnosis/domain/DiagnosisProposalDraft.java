package io.sentinelops.api.diagnosis.domain;

import io.sentinelops.api.knowledge.domain.RiskLevel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record DiagnosisProposalDraft(
        String summary,
        List<Hypothesis> hypotheses,
        List<String> missingEvidence,
        UUID runbookVersionId,
        Map<String, Object> parameters,
        RiskLevel riskLevel,
        VerificationExpectation expectedVerification) {

    public DiagnosisProposalDraft {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        hypotheses = List.copyOf(Objects.requireNonNull(hypotheses, "hypotheses"));
        missingEvidence = List.copyOf(Objects.requireNonNull(missingEvidence, "missingEvidence"));
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        Objects.requireNonNull(riskLevel, "riskLevel");
    }
}
