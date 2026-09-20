package io.sentinelops.api.diagnosis.domain;

import io.sentinelops.api.knowledge.domain.RiskLevel;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ValidatedDiagnosisProposal(
        String summary,
        List<Hypothesis> hypotheses,
        List<String> missingEvidence,
        UUID runbookVersionId,
        Map<String, Object> parameters,
        RiskLevel riskLevel,
        VerificationExpectation expectedVerification) {

    public ValidatedDiagnosisProposal {
        hypotheses = List.copyOf(hypotheses);
        missingEvidence = List.copyOf(missingEvidence);
        parameters = Map.copyOf(parameters);
    }
}
