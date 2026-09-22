package io.sentinelops.api.audit.eval;

import io.sentinelops.api.diagnosis.application.model.ModelPayloadHash;
import io.sentinelops.api.diagnosis.domain.*;
import io.sentinelops.api.incident.application.evidence.*;
import io.sentinelops.api.knowledge.application.*;
import io.sentinelops.api.knowledge.domain.*;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** In-memory snapshots only: Eval cannot acquire production evidence or change incident state. */
final class EvalFixture implements EvidenceCapture, RunbookLookup, KnowledgeSearch {
    private final UUID incidentId, serviceId;
    private final boolean serviceKnown;
    private final Instant from, to;
    private final List<DiagnosisEvidence> evidence;
    private final List<RunbookVersion> runbooks;
    private final Map<String, String> sourceFailures;
    private final String corpus;

    private EvalFixture(UUID incidentId, UUID serviceId, boolean serviceKnown, Instant from, Instant to,
            List<DiagnosisEvidence> evidence, List<RunbookVersion> runbooks, Map<String, String> sourceFailures) {
        this.incidentId = incidentId; this.serviceId = serviceId; this.serviceKnown = serviceKnown;
        this.from = from; this.to = to; this.evidence = List.copyOf(evidence); this.runbooks = List.copyOf(runbooks);
        this.sourceFailures = Map.copyOf(sourceFailures);
        this.corpus = ModelPayloadHash.hash(runbooks);
    }

    static EvalFixture parse(JsonNode input, ObjectMapper mapper) {
        EvalDatasetImporter.exact(input, Set.of("incidentId", "serviceId", "serviceKnown", "evidenceFrom", "evidenceTo", "evidence", "runbooks", "sourceFailures"));
        UUID incident = UUID.fromString(text(input, "incidentId")), service = UUID.fromString(text(input, "serviceId"));
        if (!input.get("serviceKnown").isBoolean()) throw new IllegalArgumentException("Invalid fixture service");
        Instant from = Instant.parse(text(input, "evidenceFrom")), to = Instant.parse(text(input, "evidenceTo"));
        if (!from.isBefore(to) || java.time.Duration.between(from, to).toMinutes() > 15) throw new IllegalArgumentException("Invalid fixture window");
        var evidence = new ArrayList<DiagnosisEvidence>();
        var ids = new HashSet<UUID>();
        var nodes = input.get("evidence");
        if (!nodes.isArray() || nodes.size() > 50) throw new IllegalArgumentException("Invalid fixture evidence");
        for (var node : nodes) {
            EvalDatasetImporter.exact(node, Set.of("id", "sourceType", "sourceRef", "capturedAt", "payload"));
            UUID id = UUID.fromString(text(node, "id"));
            if (!ids.add(id) || !Set.of("prometheus", "loki").contains(text(node, "sourceType")) || !node.get("payload").isObject()) throw new IllegalArgumentException("Invalid evidence identity");
            evidence.add(new DiagnosisEvidence(id, text(node, "sourceType"), text(node, "sourceRef"), node.get("payload"),
                    ModelPayloadHash.hash(node.get("payload")), Instant.parse(text(node, "capturedAt")), false));
        }
        var books = new ArrayList<RunbookVersion>();
        ids.clear();
        if (!input.get("runbooks").isArray() || input.get("runbooks").size() > 20) throw new IllegalArgumentException("Invalid fixture Runbooks");
        for (var node : input.get("runbooks")) {
            EvalDatasetImporter.exact(node, Set.of("id", "runbookId", "runbookKey", "serviceId", "versionNumber", "lifecycle", "riskLevel", "adapterId", "definition", "publishedAt"));
            UUID id = UUID.fromString(text(node, "id"));
            if (!ids.add(id) || !node.get("versionNumber").isIntegralNumber() || !node.get("definition").isObject()) throw new IllegalArgumentException("Invalid Runbook identity");
            books.add(new RunbookVersion(id, UUID.fromString(text(node, "runbookId")), text(node, "runbookKey"),
                    UUID.fromString(text(node, "serviceId")), node.get("versionNumber").asInt(),
                    RunbookVersion.Lifecycle.valueOf(text(node, "lifecycle")), RiskLevel.valueOf(text(node, "riskLevel")),
                    text(node, "adapterId"), node.get("definition"), ModelPayloadHash.hash(node.get("definition")),
                    node.get("publishedAt").isNull() ? null : Instant.parse(text(node, "publishedAt"))));
        }
        var failures = new HashMap<String, String>();
        if (!input.get("sourceFailures").isObject() || input.get("sourceFailures").size() > 10) throw new IllegalArgumentException("Invalid source failures");
        input.get("sourceFailures").properties().forEach(entry -> {
            String code = entry.getValue().asString();
            if (!entry.getKey().matches("[a-zA-Z][a-zA-Z0-9._-]{0,127}") || !Set.of("EVIDENCE_SOURCE_TIMEOUT", "EVIDENCE_SOURCE_UNAVAILABLE").contains(code)) throw new IllegalArgumentException("Unknown source failure");
            failures.put(entry.getKey(), code);
        });
        return new EvalFixture(incident, service, input.get("serviceKnown").asBoolean(), from, to, evidence, books, failures);
    }

    DiagnosisContext context() { return new DiagnosisContext(incidentId, 1, serviceId, evidence, incidentId, from, to, corpus); }
    boolean serviceKnown() { return serviceKnown; }
    Set<UUID> evidenceIds() { return evidence.stream().map(DiagnosisEvidence::id).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    Set<UUID> runbookIds() { return runbooks.stream().map(RunbookVersion::id).collect(java.util.stream.Collectors.toUnmodifiableSet()); }

    @Override public List<EvidenceSnapshot> captureAndFreeze(UUID incident, UUID run, EvidencePlan plan) {
        scope(incident, run, plan.serviceId());
        if (!from.equals(plan.from()) || !to.equals(plan.to())) throw new IllegalArgumentException("Fixture window mismatch");
        return plan.requests().stream().map(request -> {
            String failure = sourceFailures.get(request.queryId());
            if (failure != null) throw new ApiProblemException(HttpStatus.BAD_GATEWAY, failure, "Recorded evidence source failed.");
            var item = evidence.stream().filter(value -> value.sourceType().equals(request.sourceType()) && value.sourceRef().equals(request.queryId()))
                    .findFirst().orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND", "No recorded evidence for this query."));
            if (item.redactedPayload().size() > plan.budget().maxItems()) throw new EvidenceBudgetExceeded("Fixture item budget exceeded");
            return snapshot(item);
        }).toList();
    }
    @Override public EvidenceSnapshot getEvidence(UUID incident, UUID run, UUID service, UUID id) {
        scope(incident, run, service);
        return snapshot(evidence.stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND", "Evidence is outside the fixture.")));
    }
    private EvidenceSnapshot snapshot(DiagnosisEvidence item) {
        return new EvidenceSnapshot(item.id(), item.sourceType(), item.sourceRef(), item.capturedAt(), item.contentHash(), false, 0, Set.of(), item.redactedPayload());
    }
    private void scope(UUID incident, UUID run, UUID service) {
        if (!incidentId.equals(incident) || !incidentId.equals(run) || !serviceId.equals(service)) throw new IllegalArgumentException("Fixture scope mismatch");
    }
    @Override public Optional<RunbookVersion> findVersion(UUID id) { return runbooks.stream().filter(value -> value.id().equals(id)).findFirst(); }
    @Override public Optional<RunbookVersion> findPublished(String key, UUID service) {
        return runbooks.stream().filter(value -> value.serviceId().equals(service) && value.runbookKey().equals(key)
                && value.lifecycle() == RunbookVersion.Lifecycle.PUBLISHED).max(Comparator.comparing(RunbookVersion::versionNumber));
    }
    @Override public String publishedCorpusVersion(UUID service) {
        if (!serviceId.equals(service)) throw new IllegalArgumentException("Fixture service mismatch");
        return corpus;
    }
    @Override public List<KnowledgeHit> search(KnowledgeQuery query, int limit) {
        if (!serviceId.equals(query.serviceId()) || limit < 1 || limit > 10) throw new IllegalArgumentException("Fixture search scope mismatch");
        return runbooks.stream().filter(book -> book.serviceId().equals(query.serviceId()) && book.lifecycle() == RunbookVersion.Lifecycle.PUBLISHED)
                .sorted(Comparator.comparing(RunbookVersion::runbookKey)).limit(limit)
                .map(book -> new KnowledgeHit("eval-fixture", book.runbookKey(), book.id(), book.versionNumber(), book.id(), 0,
                        book.definition().toString(), 1L, null, 1.0, null, 1.0/61)).toList();
    }
    private static String text(JsonNode node, String key) {
        if (!node.path(key).isString() || node.path(key).asString().isBlank()) throw new IllegalArgumentException("Invalid fixture text");
        return node.path(key).asString();
    }
}
