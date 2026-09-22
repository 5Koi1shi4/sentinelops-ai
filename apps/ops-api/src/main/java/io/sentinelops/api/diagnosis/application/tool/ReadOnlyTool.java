package io.sentinelops.api.diagnosis.application.tool;

import tools.jackson.databind.JsonNode;

/** A server-registered diagnosis tool that can only read bounded evidence. */
public sealed interface ReadOnlyTool
        permits MetricReadTool, LogReadTool, EvidenceReadTool {

    String name();

    ToolResult invoke(JsonNode validatedInput, ToolContext context);
}
