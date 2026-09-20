package io.sentinelops.api.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.api.diagnosis.application.DiagnosisPolicy;
import io.sentinelops.api.diagnosis.application.ProposalHasher;
import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisEvidence;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import io.sentinelops.api.diagnosis.domain.Hypothesis;
import io.sentinelops.api.diagnosis.domain.InvalidProposalException;
import io.sentinelops.api.diagnosis.domain.UnsupportedRiskException;
import io.sentinelops.api.diagnosis.domain.VerificationExpectation;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import io.sentinelops.api.knowledge.domain.RunbookVersion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DiagnosisPolicyTest {

    private static final UUID INCIDENT_ID = UUID.fromString("10000000-0000-7000-8000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("10000000-0000-7000-8000-000000000002");
    private static final UUID RUNBOOK_ID = UUID.fromString("10000000-0000-7000-8000-000000000003");
    private static final UUID RUNBOOK_VERSION_ID =
            UUID.fromString("10000000-0000-7000-8000-000000000004");
    private static final UUID KNOWN_EVIDENCE =
            UUID.fromString("10000000-0000-7000-8000-000000000005");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DiagnosisPolicy policy = new DiagnosisPolicy();
    private final ProposalHasher hasher = new ProposalHasher(objectMapper);

    @Test
    void rejectsUnknownEvidenceReference() {
        var unknownEvidence = UUID.fromString("10000000-0000-7000-8000-000000000099");
        var draft = proposalWithEvidence(unknownEvidence, RiskLevel.R1, Map.of("replicas", 1));

        assertThatThrownBy(() -> policy.validate(draft, contextWithEvidence(KNOWN_EVIDENCE), publishedRunbook()))
                .isInstanceOf(InvalidProposalException.class)
                .hasMessageContaining("EVIDENCE_REFERENCE_UNKNOWN");
    }

    @Test
    void rejectsR3EvenWhenRunbookIdExists() {
        var draft = proposalWithEvidence(KNOWN_EVIDENCE, RiskLevel.R3, Map.of("replicas", 1));

        assertThatThrownBy(() -> policy.validate(draft, contextWithEvidence(KNOWN_EVIDENCE), publishedRunbook()))
                .isInstanceOf(UnsupportedRiskException.class);
    }

    @Test
    void rejectsAutomatedActionWithoutEvidenceBackedHypothesis() {
        var draft = new DiagnosisProposalDraft(
                "Scale the connection pool",
                List.of(),
                List.of(),
                RUNBOOK_VERSION_ID,
                Map.of("replicas", 1),
                RiskLevel.R1,
                new VerificationExpectation(
                        "demo_checkout_health", new BigDecimal("1.0"), 6, 5));

        assertThatThrownBy(() -> policy.validate(
                        draft, contextWithEvidence(KNOWN_EVIDENCE), publishedRunbook()))
                .isInstanceOf(InvalidProposalException.class)
                .hasMessageContaining("ACTION_EVIDENCE_REQUIRED");
    }

    @Test
    void rejectsAutomatedActionThatStillDeclaresMissingEvidence() {
        var supported = proposalWithEvidence(
                KNOWN_EVIDENCE, RiskLevel.R1, Map.of("replicas", 1));
        var draft = new DiagnosisProposalDraft(
                supported.summary(),
                supported.hypotheses(),
                List.of("database saturation duration"),
                supported.runbookVersionId(),
                supported.parameters(),
                supported.riskLevel(),
                supported.expectedVerification());

        assertThatThrownBy(() -> policy.validate(
                        draft, contextWithEvidence(KNOWN_EVIDENCE), publishedRunbook()))
                .isInstanceOf(InvalidProposalException.class)
                .hasMessageContaining("ACTION_EVIDENCE_INCOMPLETE");
    }

    @Test
    void canonicalHashIgnoresObjectPropertyOrderButNotParameters() {
        var firstOrder = new LinkedHashMap<String, Object>();
        firstOrder.put("replicas", 1);
        firstOrder.put("reason", "pool exhausted");
        var oppositeOrder = new TreeMap<String, Object>(java.util.Comparator.reverseOrder());
        oppositeOrder.putAll(firstOrder);

        var first = policy.validate(
                proposalWithEvidence(KNOWN_EVIDENCE, RiskLevel.R1, firstOrder),
                contextWithEvidence(KNOWN_EVIDENCE),
                publishedRunbookAllowingReason());
        var reordered = policy.validate(
                proposalWithEvidence(KNOWN_EVIDENCE, RiskLevel.R1, oppositeOrder),
                contextWithEvidence(KNOWN_EVIDENCE),
                publishedRunbookAllowingReason());
        var changed = policy.validate(
                proposalWithEvidence(
                        KNOWN_EVIDENCE,
                        RiskLevel.R1,
                        Map.of("replicas", 1, "reason", "different")),
                contextWithEvidence(KNOWN_EVIDENCE),
                publishedRunbookAllowingReason());

        assertThat(hasher.hash(first))
                .isEqualTo(hasher.hash(reordered))
                .isNotEqualTo(hasher.hash(changed))
                .matches("^[A-Za-z0-9_-]{43}$");
    }

    @Test
    void runbookDefinitionCannotBeMutatedThroughItsAccessor() {
        var runbook = publishedRunbook();

        ((tools.jackson.databind.node.ObjectNode) runbook.definition())
                .put("tampered", true);

        assertThat(runbook.definition().has("tampered")).isFalse();
    }

    private DiagnosisProposalDraft proposalWithEvidence(
            UUID evidenceId, RiskLevel riskLevel, Map<String, Object> parameters) {
        return new DiagnosisProposalDraft(
                "Connection acquisition is timing out",
                List.of(new Hypothesis(
                        1,
                        "The checkout pool is exhausted",
                        new BigDecimal("0.94"),
                        List.of(evidenceId))),
                List.of(),
                RUNBOOK_VERSION_ID,
                parameters,
                riskLevel,
                new VerificationExpectation(
                        "demo_checkout_health", new BigDecimal("1.0"), 6, 5));
    }

    private DiagnosisContext contextWithEvidence(UUID evidenceId) {
        var payload = objectMapper.createObjectNode()
                .put("db_pool_pending", 7)
                .put("acquire_timeout_count", 12);
        return new DiagnosisContext(
                INCIDENT_ID,
                0,
                SERVICE_ID,
                List.of(new DiagnosisEvidence(
                        evidenceId,
                        "metrics",
                        "prometheus://checkout",
                        payload,
                        "sha256:evidence",
                        Instant.parse("2026-09-20T02:00:00Z"),
                        false)));
    }

    private RunbookVersion publishedRunbook() {
        return runbookWithParameterSchema(false);
    }

    private RunbookVersion publishedRunbookAllowingReason() {
        return runbookWithParameterSchema(true);
    }

    private RunbookVersion runbookWithParameterSchema(boolean allowReason) {
        var definition = objectMapper.createObjectNode();
        var parameters = definition.putObject("parameters")
                .put("type", "object")
                .put("additionalProperties", false);
        var properties = parameters.putObject("properties");
        properties.putObject("replicas")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", 1);
        parameters.putArray("required").add("replicas");
        if (allowReason) {
            properties.putObject("reason").put("type", "string");
        }
        definition.putObject("verification")
                .put("probe", "demo_checkout_health")
                .put("successThreshold", new BigDecimal("1.0"))
                .put("attempts", 6)
                .put("intervalSeconds", 5);

        return new RunbookVersion(
                RUNBOOK_VERSION_ID,
                RUNBOOK_ID,
                "RB-DB-POOL-03",
                SERVICE_ID,
                1,
                RunbookVersion.Lifecycle.PUBLISHED,
                RiskLevel.R1,
                "demo-http",
                definition,
                "sha256:definition",
                Instant.parse("2026-09-20T01:00:00Z"));
    }
}
