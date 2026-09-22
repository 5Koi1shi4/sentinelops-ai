package io.sentinelops.api.diagnosis.application.tool;

import io.sentinelops.api.incident.application.evidence.EvidenceBudget;
import io.sentinelops.api.incident.application.evidence.EvidenceCapture;
import io.sentinelops.api.incident.application.evidence.EvidencePlan;
import io.sentinelops.api.incident.application.evidence.EvidenceRequest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Stable registry and facade for the three server-owned evidence tools. */
public final class EvidenceTools {

    private static final Set<String> QUERY_FIELDS = Set.of("queryId", "parameters");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("evidenceId");
    private static final Set<String> RESERVED_PARAMETER_NAMES = Set.of(
            "url", "baseurl", "incidentid", "serviceid", "from", "to", "window",
            "scope", "query", "queryexpression", "expression");

    private final List<ReadOnlyTool> readOnlyTools;

    public EvidenceTools(EvidenceCapture capture, EvidenceBudget budget) {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(budget, "budget");
        this.readOnlyTools = List.of(
                new MetricReadTool(capture, budget),
                new LogReadTool(capture, budget),
                new EvidenceReadTool(capture));
    }

    public ToolResult queryMetrics(JsonNode input, ToolContext context) {
        return readOnlyTools.get(0).invoke(input, context);
    }

    public ToolResult queryLogs(JsonNode input, ToolContext context) {
        return readOnlyTools.get(1).invoke(input, context);
    }

    public ToolResult getEvidence(JsonNode input, ToolContext context) {
        return readOnlyTools.get(2).invoke(input, context);
    }

    /** Returns the fixed server registration set in invocation order. */
    public List<ReadOnlyTool> readOnlyTools() {
        return readOnlyTools;
    }

    static ToolResult capture(
            EvidenceCapture capture,
            EvidenceBudget budget,
            String sourceType,
            JsonNode input,
            ToolContext context) {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(context, "context");
        var request = parseQuery(input, sourceType);
        var plan = new EvidencePlan(
                context.serviceId(),
                context.evidenceFrom(),
                context.evidenceTo(),
                List.of(request),
                budget);
        var snapshots = capture.captureAndFreeze(context.incidentId(), context.runId(), plan);
        if (snapshots == null || snapshots.size() != 1) {
            throw new IllegalStateException("evidence capture must return exactly one snapshot");
        }
        return ToolResult.fromSnapshot(Objects.requireNonNull(snapshots.getFirst(), "snapshot"));
    }

    static ToolResult getEvidence(
            EvidenceCapture capture,
            JsonNode input,
            ToolContext context) {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(context, "context");
        var evidenceId = parseEvidenceId(input);
        var snapshot = capture.getEvidence(
                context.incidentId(), context.runId(), context.serviceId(), evidenceId);
        return ToolResult.fromSnapshot(Objects.requireNonNull(snapshot, "snapshot"));
    }

    private static EvidenceRequest parseQuery(JsonNode input, String sourceType) {
        requireObjectWithFields(input, QUERY_FIELDS, "query input");
        var queryId = input.get("queryId");
        if (queryId == null || !queryId.isTextual()) {
            throw new IllegalArgumentException("queryId must be a string");
        }
        var parametersNode = input.get("parameters");
        if (parametersNode == null || !parametersNode.isObject()) {
            throw new IllegalArgumentException("parameters must be an object");
        }
        if (parametersNode.size() > 16) {
            throw new IllegalArgumentException("too many query parameters");
        }
        var parameters = new LinkedHashMap<String, String>();
        parametersNode.properties().forEach(entry -> {
            if (RESERVED_PARAMETER_NAMES.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("unsupported query parameter");
            }
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("query parameters must be strings");
            }
            parameters.put(entry.getKey(), entry.getValue().asString());
        });
        try {
            return new EvidenceRequest(sourceType, queryId.asString(), parameters);
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("invalid registered evidence query");
        }
    }

    private static UUID parseEvidenceId(JsonNode input) {
        requireObjectWithFields(input, EVIDENCE_FIELDS, "evidence input");
        var evidenceId = input.get("evidenceId");
        if (evidenceId == null || !evidenceId.isTextual()) {
            throw new IllegalArgumentException("evidenceId must be a UUID string");
        }
        var value = evidenceId.asString();
        try {
            var parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("evidenceId must use canonical UUID syntax");
            }
            return parsed;
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("evidenceId must be a UUID string");
        }
    }

    private static void requireObjectWithFields(
            JsonNode input, Set<String> expectedFields, String description) {
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException(description + " must be an object");
        }
        var actualFields = new HashSet<String>();
        input.properties().forEach(entry -> actualFields.add(entry.getKey()));
        if (!actualFields.equals(expectedFields)) {
            throw new IllegalArgumentException(description + " contains unsupported fields");
        }
    }
}
