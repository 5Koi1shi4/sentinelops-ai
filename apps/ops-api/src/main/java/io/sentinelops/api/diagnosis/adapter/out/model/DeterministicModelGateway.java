package io.sentinelops.api.diagnosis.adapter.out.model;

import io.sentinelops.api.diagnosis.application.DeterministicDiagnosisEngine;
import io.sentinelops.api.diagnosis.application.model.*;
import io.sentinelops.api.diagnosis.application.tool.EvidenceTools;
import io.sentinelops.api.diagnosis.application.tool.ToolResult;
import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** CI/Demo rules only; this adapter makes no semantic-model quality claim. */
public final class DeterministicModelGateway implements ModelGateway {
    private final DeterministicDiagnosisEngine rules;
    private final EvidenceTools realEvidence;
    private final ObjectMapper mapper;

    public DeterministicModelGateway(DeterministicDiagnosisEngine rules) {
        this(rules, null, null);
    }

    /** Real mode follows the same bounded, server-scoped read tools as semantic providers. */
    public DeterministicModelGateway(DeterministicDiagnosisEngine rules,
            EvidenceTools realEvidence, ObjectMapper mapper) {
        this.rules = Objects.requireNonNull(rules);
        this.realEvidence = realEvidence;
        this.mapper = realEvidence == null ? mapper : Objects.requireNonNull(mapper);
    }

    @Override public ModelDiagnosisResult diagnose(ModelDiagnosisRequest request) {
        long started = System.nanoTime();
        var context = request.context();
        int toolCalls = 0;
        if (realEvidence != null) {
            if (request.budget().maxTotalCalls() < 4) {
                throw new IllegalArgumentException("real evidence requires four bounded read calls");
            }
            var evidence = new ArrayList<>(context.evidence());
            for (var query : List.of(
                    Map.entry("prometheus", "checkout_error_rate"),
                    Map.entry("prometheus", "checkout_latency_mean"),
                    Map.entry("prometheus", "pool_pending"),
                    Map.entry("loki", "acquire_timeout_logs"))) {
                var input = mapper.createObjectNode().put("queryId", query.getValue());
                input.set("parameters", mapper.createObjectNode());
                ToolResult result = query.getKey().equals("loki")
                        ? realEvidence.queryLogs(input, request.toolContext())
                        : realEvidence.queryMetrics(input, request.toolContext());
                toolCalls++;
                evidence.add(new DiagnosisEvidence(result.evidenceId(), result.sourceType(),
                        result.queryId(), result.payload(), result.contentHash(),
                        result.capturedAt(), result.truncated()));
            }
            context = new DiagnosisContext(context.incidentId(), context.incidentVersion(),
                    context.serviceId(), evidence, context.runId(), context.evidenceFrom(),
                    context.evidenceTo(), context.runbookCorpusVersion());
        }
        var draft = rules.diagnose(context);
        return new ModelDiagnosisResult(draft,"deterministic","deterministic-v1",request.promptVersion(),
                request.context().runbookCorpusVersion(),ModelPayloadHash.hash(request),ModelPayloadHash.hash(draft),
                0,0,toolCalls,"stop",(System.nanoTime()-started)/1_000_000);
    }
}
