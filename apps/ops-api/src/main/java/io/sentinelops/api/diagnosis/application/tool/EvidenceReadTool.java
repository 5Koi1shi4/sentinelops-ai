package io.sentinelops.api.diagnosis.application.tool;

import io.sentinelops.api.incident.application.evidence.EvidenceCapture;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Read-only frozen evidence lookup tool. */
public final class EvidenceReadTool implements ReadOnlyTool {

    private final EvidenceCapture capture;

    public EvidenceReadTool(EvidenceCapture capture) {
        this.capture = Objects.requireNonNull(capture, "capture");
    }

    @Override
    public String name() {
        return "getEvidence";
    }

    @Override
    public ToolResult invoke(JsonNode validatedInput, ToolContext context) {
        return EvidenceTools.getEvidence(capture, validatedInput, context);
    }
}
