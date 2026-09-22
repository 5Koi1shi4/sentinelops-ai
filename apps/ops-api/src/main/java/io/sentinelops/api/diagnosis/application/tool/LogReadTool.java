package io.sentinelops.api.diagnosis.application.tool;

import io.sentinelops.api.incident.application.evidence.EvidenceBudget;
import io.sentinelops.api.incident.application.evidence.EvidenceCapture;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Read-only Loki evidence tool. */
public final class LogReadTool implements ReadOnlyTool {

    private final EvidenceCapture capture;
    private final EvidenceBudget budget;

    public LogReadTool(EvidenceCapture capture, EvidenceBudget budget) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    @Override
    public String name() {
        return "queryLogs";
    }

    @Override
    public ToolResult invoke(JsonNode validatedInput, ToolContext context) {
        return EvidenceTools.capture(capture, budget, "loki", validatedInput, context);
    }
}
