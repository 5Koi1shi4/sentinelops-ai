package io.sentinelops.api.incident.application.evidence;

import io.sentinelops.api.incident.adapter.out.persistence.EvidenceStore;
import io.sentinelops.api.shared.id.UuidV7Generator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Capture outside transactions, then atomically freeze only redacted, bounded evidence. */
public class EvidenceCaptureService implements EvidenceCapture {
    private final Map<String, EvidenceSource> sources;
    private final EvidenceRedactor redactor;
    private final EvidenceStore store;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final UuidV7Generator ids;

    public EvidenceCaptureService(List<EvidenceSource> sources, EvidenceRedactor redactor, EvidenceStore store,
                                  ObjectMapper mapper, PlatformTransactionManager transactions, UuidV7Generator ids) {
        var registered = new HashMap<String, EvidenceSource>();
        for (var source : sources) {
            if (registered.putIfAbsent(source.sourceType(), source) != null) {
                throw new IllegalArgumentException("duplicate evidence source");
            }
        }
        this.sources = Map.copyOf(registered);
        this.redactor = Objects.requireNonNull(redactor);
        this.store = Objects.requireNonNull(store);
        this.mapper = Objects.requireNonNull(mapper);
        this.transaction = new TransactionTemplate(transactions);
        this.ids = Objects.requireNonNull(ids);
    }

    public List<EvidenceSnapshot> captureAndFreeze(UUID incidentId, UUID diagnosisRunId, EvidencePlan plan) {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(diagnosisRunId, "diagnosisRunId");
        Objects.requireNonNull(plan, "plan");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("evidence capture requires a transaction-free caller");
        }
        for (var request : plan.requests()) {
            if (!sources.containsKey(request.sourceType())) throw new IllegalArgumentException("unconfigured evidence source");
        }
        transaction.executeWithoutResult(status -> store.requireRunningScope(incidentId, diagnosisRunId, plan.serviceId(), false));
        var candidates = new ArrayList<EvidenceSnapshot>();
        var acquisitionBudget = new EvidenceBudget(EvidenceBudget.MAX_ITEMS, EvidenceBudget.MAX_BYTES, plan.budget().maxWindow());
        for (var request : plan.requests()) {
            var query = new EvidenceQuery(incidentId, plan.serviceId(), request.queryId(), request.parameters(), plan.from(), plan.to());
            var captured = sources.get(request.sourceType()).capture(query, acquisitionBudget);
            requireCapturedScope(captured, query, request.sourceType());
            if (captured.truncated()) throw new EvidenceBudgetExceeded("evidence exceeds server acquisition budget");
            candidates.add(redactAndBound(captured, request, plan.budget()));
        }
        return transaction.execute(status -> {
            store.requireRunningScope(incidentId, diagnosisRunId, plan.serviceId(), true);
            return candidates.stream().map(candidate -> store.freeze(incidentId, diagnosisRunId, candidate)).toList();
        });
    }

    public EvidenceSnapshot getEvidence(UUID incidentId, UUID runId, UUID serviceId, UUID evidenceId) {
        Objects.requireNonNull(incidentId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(serviceId);
        Objects.requireNonNull(evidenceId);
        return store.getLinked(incidentId, runId, serviceId, evidenceId);
    }

    private EvidenceSnapshot redactAndBound(CapturedEvidence captured, EvidenceRequest request, EvidenceBudget budget) {
        var raw = mapper.createObjectNode();
        raw.put("sourceType", captured.sourceType());
        raw.put("queryId", captured.queryId());
        raw.put("from", captured.from().toString());
        raw.put("to", captured.to().toString());
        raw.set("parameters", mapper.valueToTree(request.parameters()));
        raw.set("items", mapper.valueToTree(captured.items()));
        raw.set("warnings", mapper.valueToTree(captured.warnings()));
        var redacted = redactor.redact(raw);
        if (!(redacted.json() instanceof ObjectNode payload)
                || !payload.path("items").isArray() || !payload.path("warnings").isArray()) {
            throw new EvidenceSourceException("redaction policy removed required evidence structure");
        }
        payload.put("truncated", captured.truncated() || redacted.truncated());
        var metadata = mapper.createObjectNode().put("count", redacted.count());
        metadata.set("rules", mapper.valueToTree(new java.util.TreeSet<>(redacted.appliedRules())));
        payload.set("redaction", metadata);
        sortRedactedArrays(payload);
        if (payload.path("items").size() > budget.maxItems()) {
            payload.set("items", prefix((ArrayNode) payload.get("items"), budget.maxItems()));
            payload.put("truncated", true);
        }
        capPayload(payload, budget.maxBytes());
        var canonical = canonical(payload);
        return new EvidenceSnapshot(ids.generate(), captured.sourceType(), captured.queryId(), captured.capturedAt(),
                hash(mapper.writeValueAsBytes(canonical)), canonical.path("truncated").asBoolean(),
                redacted.count(), redacted.appliedRules(), canonical);
    }

    private void sortRedactedArrays(ObjectNode payload) {
        // 原始敏感标签可能影响来源排序；脱敏后重新排序才可计算快照身份。
        record SafeItem(java.math.BigDecimal timestamp, String canonicalKey, JsonNode value) {}
        var items = new ArrayList<SafeItem>();
        for (var item : payload.get("items")) {
            var safe = canonical(item);
            String timestamp = safe.path("timestamp").asString("");
            var numeric = timestamp.length() <= 32 && timestamp.matches("[0-9]+(?:\\.[0-9]{1,9})?")
                    ? new java.math.BigDecimal(timestamp) : java.math.BigDecimal.ZERO;
            items.add(new SafeItem(numeric, mapper.writeValueAsString(safe), safe));
        }
        items.sort(java.util.Comparator.comparing(SafeItem::timestamp).thenComparing(SafeItem::canonicalKey));
        var orderedItems = mapper.createArrayNode();
        items.forEach(item -> orderedItems.add(item.value()));
        payload.set("items", orderedItems);
        var warnings = new ArrayList<JsonNode>();
        payload.get("warnings").forEach(warning -> warnings.add(canonical(warning)));
        warnings.sort(java.util.Comparator.comparing(mapper::writeValueAsString));
        var orderedWarnings = mapper.createArrayNode();
        warnings.forEach(orderedWarnings::add);
        payload.set("warnings", orderedWarnings);
    }

    private void capPayload(ObjectNode payload, int maxBytes) {
        if (mapper.writeValueAsBytes(payload).length <= maxBytes) return;
        payload.put("truncated", true);
        var items = (ArrayNode) payload.get("items");
        var warnings = (ArrayNode) payload.get("warnings");
        payload.set("items", mapper.createArrayNode());
        payload.set("warnings", mapper.createArrayNode());
        if (mapper.writeValueAsBytes(payload).length > maxBytes) {
            throw new EvidenceBudgetExceeded("redacted evidence metadata exceeds byte budget");
        }
        retainPrefix(payload, "items", items, maxBytes);
        retainPrefix(payload, "warnings", warnings, maxBytes);
    }

    private void retainPrefix(ObjectNode payload, String field, ArrayNode values, int maxBytes) {
        int low = 0;
        int high = values.size();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            payload.set(field, prefix(values, mid));
            if (mapper.writeValueAsBytes(payload).length <= maxBytes) low = mid;
            else high = mid - 1;
        }
        payload.set(field, prefix(values, low));
    }

    private ArrayNode prefix(ArrayNode values, int count) {
        var result = mapper.createArrayNode();
        for (int index = 0; index < count; index++) result.add(values.get(index));
        return result;
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            var result = mapper.createObjectNode();
            sorted.forEach((key, value) -> result.set(key, canonical(value)));
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node.deepCopy();
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
    }

    private static void requireCapturedScope(CapturedEvidence value, EvidenceQuery query, String source) {
        if (value == null || !value.incidentId().equals(query.incidentId()) || !value.serviceId().equals(query.serviceId())
                || !value.sourceType().equals(source) || !value.queryId().equals(query.queryId())
                || !value.from().equals(query.from()) || !value.to().equals(query.to())) {
            throw new EvidenceSourceException("evidence source returned a different scope");
        }
    }
}
