package io.sentinelops.api.incident.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record IncidentCockpitView(
        IncidentSummary incident,
        DiagnosisView diagnosis,
        List<EvidenceReference> evidence,
        ApprovalView activeApproval,
        ExecutionView latestExecution) {

    public IncidentCockpitView {
        evidence = List.copyOf(evidence);
    }

    public record DiagnosisView(
            UUID id,
            UUID diagnosisRunId,
            long incidentVersion,
            String summary,
            JsonNode hypotheses,
            JsonNode missingEvidence,
            UUID runbookVersionId,
            Integer runbookVersion,
            String runbookKey,
            String runbookName,
            JsonNode parameters,
            String riskLevel,
            JsonNode expectedVerification,
            String proposalHash,
            Instant createdAt) {}

    public record EvidenceReference(
            UUID id,
            String sourceType,
            String sourceRef,
            String contentHash,
            Instant capturedAt,
            boolean truncated) {}

    public record ApprovalView(
            UUID id,
            UUID proposalId,
            String proposalHash,
            String targetAlias,
            String status,
            long resourceVersion,
            int requiredApprovals,
            int approvals,
            int rejections,
            UUID requesterId,
            String requesterSubject,
            String requesterDisplayName,
            boolean independentApproverRequired,
            Instant createdAt,
            Instant expiresAt,
            Instant decidedAt) {}

    public record ExecutionView(
            UUID id,
            UUID proposalId,
            UUID approvalRequestId,
            String status,
            String targetAlias,
            long fencingToken,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant completedAt) {}
}
