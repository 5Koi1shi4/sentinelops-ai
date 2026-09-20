package io.sentinelops.api.diagnosis.application;

import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import io.sentinelops.api.diagnosis.domain.InvalidProposalException;
import io.sentinelops.api.diagnosis.domain.UnsupportedRiskException;
import io.sentinelops.api.diagnosis.domain.ValidatedDiagnosisProposal;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import io.sentinelops.api.knowledge.domain.RunbookVersion;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class DiagnosisPolicy {

    public ValidatedDiagnosisProposal validate(
            DiagnosisProposalDraft draft,
            DiagnosisContext context,
            RunbookVersion runbook) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(context, "context");
        if (draft.riskLevel() == RiskLevel.R3) {
            throw new UnsupportedRiskException();
        }

        validateEvidenceReferences(draft, context);
        validateMissingEvidence(draft);
        validateAction(draft, context, runbook);

        return new ValidatedDiagnosisProposal(
                draft.summary(),
                draft.hypotheses(),
                draft.missingEvidence(),
                draft.runbookVersionId(),
                draft.parameters(),
                draft.riskLevel(),
                draft.expectedVerification());
    }

    private void validateEvidenceReferences(
            DiagnosisProposalDraft draft, DiagnosisContext context) {
        Set<UUID> availableEvidence = new HashSet<>();
        context.evidence().forEach(evidence -> availableEvidence.add(evidence.id()));

        var ranks = new HashSet<Integer>();
        for (int index = 0; index < draft.hypotheses().size(); index++) {
            var hypothesis = draft.hypotheses().get(index);
            if (!ranks.add(hypothesis.rank()) || hypothesis.rank() != index + 1) {
                throw invalid("HYPOTHESIS_RANK_INVALID", "hypothesis ranks must be contiguous");
            }
            if (hypothesis.confidence().compareTo(BigDecimal.ZERO) < 0
                    || hypothesis.confidence().compareTo(BigDecimal.ONE) > 0) {
                throw invalid(
                        "HYPOTHESIS_CONFIDENCE_INVALID",
                        "hypothesis confidence must be between zero and one");
            }
            if (hypothesis.evidenceRefs().isEmpty()) {
                throw invalid(
                        "EVIDENCE_REFERENCE_REQUIRED",
                        "every hypothesis must cite at least one evidence snapshot");
            }
            for (var evidenceId : hypothesis.evidenceRefs()) {
                if (!availableEvidence.contains(evidenceId)) {
                    throw invalid(
                            "EVIDENCE_REFERENCE_UNKNOWN",
                            "proposal references evidence outside the frozen context");
                }
            }
        }
    }

    private void validateMissingEvidence(DiagnosisProposalDraft draft) {
        if (draft.missingEvidence().stream().anyMatch(item -> item == null || item.isBlank())) {
            throw invalid("MISSING_EVIDENCE_INVALID", "missing-evidence entries must not be blank");
        }
    }

    private void validateAction(
            DiagnosisProposalDraft draft,
            DiagnosisContext context,
            RunbookVersion runbook) {
        if (draft.runbookVersionId() == null) {
            if (!draft.parameters().isEmpty()
                    || draft.missingEvidence().isEmpty()
                    || draft.expectedVerification() != null
                    || draft.riskLevel() != RiskLevel.R0) {
                throw invalid(
                        "ACTION_WITHOUT_RUNBOOK",
                        "a proposal without a Runbook must be R0 and describe missing evidence only");
            }
            return;
        }
        if (draft.hypotheses().isEmpty()) {
            throw invalid(
                    "ACTION_EVIDENCE_REQUIRED",
                    "an automated action requires at least one evidence-backed hypothesis");
        }
        if (!draft.missingEvidence().isEmpty()) {
            throw invalid(
                    "ACTION_EVIDENCE_INCOMPLETE",
                    "an automated action cannot be proposed while required evidence is missing");
        }
        if (runbook == null || !runbook.id().equals(draft.runbookVersionId())) {
            throw invalid("RUNBOOK_VERSION_UNKNOWN", "the proposed Runbook version does not exist");
        }
        if (runbook.lifecycle() != RunbookVersion.Lifecycle.PUBLISHED) {
            throw invalid("RUNBOOK_NOT_PUBLISHED", "only published Runbook versions may be proposed");
        }
        if (!runbook.serviceId().equals(context.serviceId())) {
            throw invalid("RUNBOOK_SERVICE_MISMATCH", "the Runbook is not registered for this service");
        }
        if (runbook.riskLevel() != draft.riskLevel()) {
            throw invalid("RISK_LEVEL_MISMATCH", "proposal risk must match the Runbook version");
        }
        validateParameters(draft.parameters(), runbook.definition().path("parameters"));
        validateVerification(draft, runbook.definition().path("verification"));
    }

    private void validateParameters(Map<String, Object> parameters, JsonNode schema) {
        if (!schema.isObject() || !"object".equals(schema.path("type").asString())) {
            throw invalid("RUNBOOK_PARAMETER_SCHEMA_INVALID", "Runbook parameter schema is invalid");
        }
        var required = new HashSet<String>();
        var requiredNode = schema.path("required");
        if (requiredNode.isArray()) {
            requiredNode.forEach(node -> required.add(node.asString()));
        }
        if (!parameters.keySet().containsAll(required)) {
            throw invalid("RUNBOOK_PARAMETER_REQUIRED", "required Runbook parameters are missing");
        }

        var properties = schema.path("properties");
        boolean additionalAllowed = schema.path("additionalProperties").asBoolean(true);
        for (var entry : parameters.entrySet()) {
            var propertySchema = properties.path(entry.getKey());
            if (propertySchema.isMissingNode()) {
                if (!additionalAllowed) {
                    throw invalid(
                            "RUNBOOK_PARAMETER_UNKNOWN",
                            "proposal contains a parameter not declared by the Runbook");
                }
                continue;
            }
            validateParameter(entry.getKey(), entry.getValue(), propertySchema);
        }
    }

    private void validateParameter(String name, Object value, JsonNode schema) {
        var type = schema.path("type").asString();
        boolean validType = switch (type) {
            case "integer" -> isInteger(value);
            case "number" -> value instanceof Number;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            default -> false;
        };
        if (!validType) {
            throw invalid("RUNBOOK_PARAMETER_TYPE", "parameter " + name + " has an invalid type");
        }
        if (value instanceof Number number) {
            var decimal = new BigDecimal(number.toString());
            if (schema.has("minimum")
                    && decimal.compareTo(schema.path("minimum").decimalValue()) < 0) {
                throw invalid("RUNBOOK_PARAMETER_RANGE", "parameter " + name + " is below minimum");
            }
            if (schema.has("maximum")
                    && decimal.compareTo(schema.path("maximum").decimalValue()) > 0) {
                throw invalid("RUNBOOK_PARAMETER_RANGE", "parameter " + name + " is above maximum");
            }
        }
    }

    private boolean isInteger(Object value) {
        return value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger;
    }

    private void validateVerification(DiagnosisProposalDraft draft, JsonNode definition) {
        var expected = draft.expectedVerification();
        if (expected == null
                || !definition.isObject()
                || !expected.probe().equals(definition.path("probe").asString())
                || expected.successThreshold().compareTo(
                                definition.path("successThreshold").decimalValue())
                        != 0
                || expected.attempts() != definition.path("attempts").asInt()
                || expected.intervalSeconds() != definition.path("intervalSeconds").asInt()) {
            throw invalid(
                    "VERIFICATION_EXPECTATION_MISMATCH",
                    "verification must match the immutable Runbook definition");
        }
    }

    private InvalidProposalException invalid(String errorCode, String detail) {
        return new InvalidProposalException(errorCode, detail);
    }
}
