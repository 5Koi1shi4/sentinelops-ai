package io.sentinelops.api.diagnosis.application;

import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisEvidence;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import io.sentinelops.api.diagnosis.domain.Hypothesis;
import io.sentinelops.api.diagnosis.domain.VerificationExpectation;
import io.sentinelops.api.knowledge.application.RunbookCatalog;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class DeterministicDiagnosisEngine implements DiagnosisEngine {

    static final String RUNBOOK_KEY = "RB-DB-POOL-03";
    private static final String PENDING_METRIC = "db_pool_pending";
    private static final String TIMEOUT_METRIC = "acquire_timeout_count";

    private final RunbookCatalog runbooks;

    public DeterministicDiagnosisEngine(RunbookCatalog runbooks) {
        this.runbooks = runbooks;
    }

    @Override
    public DiagnosisProposalDraft diagnose(DiagnosisContext context) {
        var pendingEvidence = positiveEvidence(context.evidence(), PENDING_METRIC);
        var timeoutEvidence = positiveEvidence(context.evidence(), TIMEOUT_METRIC);
        var missing = new ArrayList<String>();
        if (pendingEvidence.isEmpty()) {
            missing.add(PENDING_METRIC + " > 0");
        }
        if (timeoutEvidence.isEmpty()) {
            missing.add(TIMEOUT_METRIC + " > 0");
        }
        if (!missing.isEmpty()) {
            return new DiagnosisProposalDraft(
                    "Insufficient evidence to recommend an automated recovery action.",
                    List.of(),
                    missing,
                    null,
                    Map.of(),
                    RiskLevel.R0,
                    null);
        }

        var runbook = runbooks.findPublished(RUNBOOK_KEY, context.serviceId())
                .orElseThrow(() -> new IllegalStateException(
                        "Published Demo Runbook is missing for the incident service"));
        var references = new LinkedHashSet<UUID>();
        pendingEvidence.forEach(evidence -> references.add(evidence.id()));
        timeoutEvidence.forEach(evidence -> references.add(evidence.id()));
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

    private List<DiagnosisEvidence> positiveEvidence(
            List<DiagnosisEvidence> evidence, String field) {
        return evidence.stream()
                .filter(item -> isPositive(item.redactedPayload().findValue(field)))
                .toList();
    }

    private boolean isPositive(JsonNode value) {
        return value != null
                && value.isNumber()
                && value.decimalValue().compareTo(BigDecimal.ZERO) > 0;
    }
}
