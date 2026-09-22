package io.sentinelops.api.diagnosis.application.tool;

import io.sentinelops.api.incident.application.evidence.EvidenceBudget;
import io.sentinelops.api.incident.application.evidence.EvidenceCapture;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Read-only Prometheus evidence tool. */
public final class MetricReadTool implements ReadOnlyTool {

    private final EvidenceCapture capture;
    private final EvidenceBudget budget;

    public MetricReadTool(EvidenceCapture capture, EvidenceBudget budget) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    @Override
    public String name() {
        return "queryMetrics";
    }

    @Override
    public ToolResult invoke(JsonNode validatedInput, ToolContext context) {
        return EvidenceTools.capture(capture, budget, "prometheus", validatedInput, context);
    }
}
