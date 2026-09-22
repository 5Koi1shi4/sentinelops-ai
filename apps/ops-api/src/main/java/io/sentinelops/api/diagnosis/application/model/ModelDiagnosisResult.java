package io.sentinelops.api.diagnosis.application.model;

import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import java.util.Objects;

@org.springframework.modulith.NamedInterface("evaluation")
public record ModelDiagnosisResult(DiagnosisProposalDraft proposal, String provider, String modelName,
                                  String promptVersion, String runbookCorpusVersion, String inputHash,
                                  String responseHash, long inputTokens, long outputTokens, int toolCallCount,
                                  String finishReason, long latencyMs) {
    public ModelDiagnosisResult {
        Objects.requireNonNull(proposal, "proposal");
        for (var value : new String[]{provider,modelName,promptVersion,runbookCorpusVersion,inputHash,responseHash,finishReason}) {
            if (value == null || value.isBlank() || value.length() > 200) throw new IllegalArgumentException("Invalid model metadata");
        }
        if (inputTokens < 0 || outputTokens < 0 || toolCallCount < 0 || toolCallCount > 6 || latencyMs < 0) {
            throw new IllegalArgumentException("Invalid model usage");
        }
    }
}
