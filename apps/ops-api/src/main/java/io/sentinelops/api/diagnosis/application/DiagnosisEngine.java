package io.sentinelops.api.diagnosis.application;

import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import io.sentinelops.api.diagnosis.application.model.ModelDiagnosisResult;
import io.sentinelops.api.diagnosis.application.model.ModelPayloadHash;

public interface DiagnosisEngine {

    DiagnosisProposalDraft diagnose(DiagnosisContext context);

    default ModelDiagnosisResult diagnoseWithMetadata(DiagnosisContext context) {
        long started = System.nanoTime();
        var draft = diagnose(context);
        return new ModelDiagnosisResult(draft, "deterministic", "deterministic-v1", "diagnosis-system-v1",
                context.runbookCorpusVersion(), ModelPayloadHash.hash(context), ModelPayloadHash.hash(draft),
                0, 0, 0, "stop", (System.nanoTime()-started)/1_000_000);
    }

}
