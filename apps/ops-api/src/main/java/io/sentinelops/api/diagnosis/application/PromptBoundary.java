package io.sentinelops.api.diagnosis.application;

import io.sentinelops.api.diagnosis.application.tool.ToolResult;
import java.util.Objects;
import java.util.TreeSet;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Serializes external evidence as data without mixing it into prompt instructions. */
public final class PromptBoundary {

    private final ObjectMapper objectMapper;

    public PromptBoundary(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String serialize(ToolResult result) {
        Objects.requireNonNull(result, "result");

        var envelope = objectMapper.createObjectNode();
        envelope.put("type", "external_evidence");
        envelope.put("trust", result.trust().name());

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("evidenceId", result.evidenceId().toString());
        evidence.put("sourceType", result.sourceType());
        evidence.put("queryId", result.queryId());
        evidence.put("capturedAt", result.capturedAt().toString());
        evidence.put("contentHash", result.contentHash());
        evidence.put("truncated", result.truncated());
        evidence.put("redactionCount", result.redactionCount());
        evidence.set("appliedRules", objectMapper.valueToTree(new TreeSet<>(result.appliedRules())));
        evidence.set("payload", result.payload());
        envelope.set("evidence", evidence);

        return objectMapper.writeValueAsString(envelope);
    }
}
