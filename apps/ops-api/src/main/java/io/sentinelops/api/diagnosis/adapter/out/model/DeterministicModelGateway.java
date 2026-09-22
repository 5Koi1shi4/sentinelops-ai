package io.sentinelops.api.diagnosis.adapter.out.model;

import io.sentinelops.api.diagnosis.application.DeterministicDiagnosisEngine;
import io.sentinelops.api.diagnosis.application.model.*;

/** CI/Demo rules only; this adapter makes no semantic-model quality claim. */
public final class DeterministicModelGateway implements ModelGateway {
    private final DeterministicDiagnosisEngine rules;
    public DeterministicModelGateway(DeterministicDiagnosisEngine rules) { this.rules = java.util.Objects.requireNonNull(rules); }
    @Override public ModelDiagnosisResult diagnose(ModelDiagnosisRequest request) {
        long started = System.nanoTime();
        var draft = rules.diagnose(request.context());
        return new ModelDiagnosisResult(draft,"deterministic","deterministic-v1",request.promptVersion(),
                request.context().runbookCorpusVersion(),ModelPayloadHash.hash(request),ModelPayloadHash.hash(draft),
                0,0,0,"stop",(System.nanoTime()-started)/1_000_000);
    }
}
