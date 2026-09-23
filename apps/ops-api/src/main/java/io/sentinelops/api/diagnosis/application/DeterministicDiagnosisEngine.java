package io.sentinelops.api.diagnosis.application;

import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisEvidence;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import io.sentinelops.api.diagnosis.domain.Hypothesis;
import io.sentinelops.api.diagnosis.domain.VerificationExpectation;
import io.sentinelops.api.knowledge.application.RunbookLookup;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

public class DeterministicDiagnosisEngine implements DiagnosisEngine {

    static final String RUNBOOK_KEY = "RB-DB-POOL-03";
    private static final String PENDING_METRIC = "db_pool_pending";
    private static final String TIMEOUT_METRIC = "acquire_timeout_count";

    private final RunbookLookup runbooks;

    public DeterministicDiagnosisEngine(RunbookLookup runbooks) {
        this.runbooks = runbooks;
    }

    @Override
    public DiagnosisProposalDraft diagnose(DiagnosisContext context) {
        var freshEvidence = context.evidence().stream()
                .filter(item -> context.evidenceFrom() == null
                        || !item.capturedAt().isBefore(context.evidenceFrom()))
                .toList();
        var pendingEvidence = positiveEvidence(freshEvidence, PENDING_METRIC);
        var timeoutEvidence = positiveEvidence(freshEvidence, TIMEOUT_METRIC);
        var missing = new ArrayList<String>();
        if (pendingEvidence.isEmpty()) {
            missing.add(PENDING_METRIC + " > 0");
        }
        if (timeoutEvidence.isEmpty()) {
            missing.add(TIMEOUT_METRIC + " > 0");
        }
        var conflicting = freshEvidence.stream().filter(item -> {
            var payload = item.redactedPayload();
            var pending = payload.findValue(PENDING_METRIC);
            return pending != null && pending.isNumber()
                    && pending.decimalValue().compareTo(BigDecimal.ZERO) == 0
                    && isPositive(payload.findValue(TIMEOUT_METRIC));
        }).toList();
        if (!conflicting.isEmpty()) {
            return noAction("Pool signals conflict; verify before recovery.",
                    hypothesis("Conflicting pool signals show acquisition timeouts while pending work is zero.",
                            conflicting), List.of("consistent pool pressure evidence"));
        }
        if (!missing.isEmpty()) {
            var partial = !pendingEvidence.isEmpty() ? hypothesis(
                    "Pool saturation is possible because pending acquisitions are elevated.", pendingEvidence)
                    : !positiveEvidence(freshEvidence, "downstream_timeout_count").isEmpty()
                        ? hypothesis("Downstream timeout is supported by the observed timeout metric.",
                                positiveEvidence(freshEvidence, "downstream_timeout_count"))
                    : !positiveEvidence(freshEvidence, "cpu_percent").isEmpty()
                        ? hypothesis("High CPU is supported by the observed utilization metric.",
                                positiveEvidence(freshEvidence, "cpu_percent"))
                    : !timeoutEvidence.isEmpty()
                        ? hypothesis("Acquisition timeouts occurred without confirmed pool pressure.", timeoutEvidence)
                    : List.<Hypothesis>of();
            return noAction("Insufficient evidence to recommend an automated recovery action.",
                    partial, missing);
        }

        var runbook = runbooks.findPublished(RUNBOOK_KEY, context.serviceId())
                .orElse(null);
        var references = new LinkedHashSet<UUID>();
        pendingEvidence.forEach(evidence -> references.add(evidence.id()));
        timeoutEvidence.forEach(evidence -> references.add(evidence.id()));
        if (runbook == null) {
            return noAction("Pool saturation is supported, but no approved recovery is available.",
                    List.of(new Hypothesis(1,
                            "Pending pool requests and acquisition timeouts indicate pool saturation.",
                            new BigDecimal("0.94"), List.copyOf(references))),
                    List.of("published recovery Runbook"));
        }
        JsonNode verification = runbook.definition().path("verification");

        return new DiagnosisProposalDraft(
                "Database connection acquisition is blocked by a saturated connection pool.",
                List.of(new Hypothesis(
                        1,
                        "Pending pool requests and acquisition timeouts indicate pool saturation.",
                        new BigDecimal("0.94"),
                        List.copyOf(references))),
                List.of(),
                runbook.id(),
                Map.of("replicas", 1),
                runbook.riskLevel(),
                new VerificationExpectation(
                        verification.path("probe").asString(),
                        verification.path("successThreshold").decimalValue(),
                        verification.path("attempts").asInt(),
                        verification.path("intervalSeconds").asInt()));
    }

    private DiagnosisProposalDraft noAction(String summary, List<Hypothesis> hypotheses,
            List<String> missing) {
        return new DiagnosisProposalDraft(summary, hypotheses, missing, null,
                Map.of(), RiskLevel.R0, null);
    }

    private List<Hypothesis> hypothesis(String statement, List<DiagnosisEvidence> evidence) {
        return List.of(new Hypothesis(1, statement, new BigDecimal("0.65"),
                evidence.stream().map(DiagnosisEvidence::id).distinct().toList()));
    }

    private List<DiagnosisEvidence> positiveEvidence(
            List<DiagnosisEvidence> evidence, String field) {
        return evidence.stream()
                .filter(item -> isPositive(item.redactedPayload().findValue(field))
                        || isRealSignal(item, field))
                .toList();
    }

    private boolean isRealSignal(DiagnosisEvidence evidence, String field) {
        if (PENDING_METRIC.equals(field)
                && evidence.sourceType().equals("prometheus")
                && evidence.sourceRef().equals("pool_pending")) {
            for (var item : evidence.redactedPayload().path("items")) {
                try {
                    if (new BigDecimal(item.path("value").asString("0"))
                            .compareTo(BigDecimal.ZERO) > 0) return true;
                } catch (NumberFormatException ignored) {
                    // Malformed samples cannot establish the recovery signal.
                }
            }
        }
        if (TIMEOUT_METRIC.equals(field)
                && evidence.sourceType().equals("loki")
                && evidence.sourceRef().equals("acquire_timeout_logs")) {
            for (var item : evidence.redactedPayload().path("items")) {
                if (item.path("value").asString("").contains("Demo checkout acquire timeout")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPositive(JsonNode value) {
        return value != null
                && value.isNumber()
                && value.decimalValue().compareTo(BigDecimal.ZERO) > 0;
    }
}
