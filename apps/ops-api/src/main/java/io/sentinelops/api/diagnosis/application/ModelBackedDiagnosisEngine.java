package io.sentinelops.api.diagnosis.application;

import io.sentinelops.api.diagnosis.application.model.*;
import io.sentinelops.api.diagnosis.application.tool.ToolContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;

public final class ModelBackedDiagnosisEngine implements DiagnosisEngine {
    private final ModelGateway gateway;
    public ModelBackedDiagnosisEngine(ModelGateway gateway) { this.gateway = java.util.Objects.requireNonNull(gateway); }
    @Override public DiagnosisProposalDraft diagnose(DiagnosisContext context) { return diagnoseWithMetadata(context).proposal(); }
    @Override public ModelDiagnosisResult diagnoseWithMetadata(DiagnosisContext context) {
        if (!context.hasServerScope()) throw new IllegalArgumentException("Server-owned diagnosis scope required");
        return gateway.diagnose(new ModelDiagnosisRequest(context,
                new ToolContext(context.incidentId(),context.runId(),context.serviceId(),context.evidenceFrom(),context.evidenceTo()),
                "diagnosis-system-v1", ToolBudget.defaults()));
    }
}
