package io.sentinelops.api.diagnosis.application.model;

import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.application.tool.ToolContext;
import java.util.Objects;

@org.springframework.modulith.NamedInterface("evaluation")
public record ModelDiagnosisRequest(DiagnosisContext context, ToolContext toolContext,
                                   String promptVersion, ToolBudget budget) {
    public ModelDiagnosisRequest {
        Objects.requireNonNull(context);
        Objects.requireNonNull(toolContext);
        Objects.requireNonNull(budget);
        if (!"diagnosis-system-v1".equals(promptVersion)) throw new IllegalArgumentException("Unknown prompt version");
        if (!context.incidentId().equals(toolContext.incidentId())
                || !context.serviceId().equals(toolContext.serviceId())
                || !Objects.equals(context.runId(), toolContext.runId())
                || !Objects.equals(context.evidenceFrom(), toolContext.evidenceFrom())
                || !Objects.equals(context.evidenceTo(), toolContext.evidenceTo())) {
            throw new IllegalArgumentException("Tool scope must match the frozen diagnosis context");
        }
    }
}
