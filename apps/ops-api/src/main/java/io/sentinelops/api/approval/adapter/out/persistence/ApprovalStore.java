package io.sentinelops.api.approval.adapter.out.persistence;

import io.sentinelops.api.approval.domain.ApprovalDecision;
import io.sentinelops.api.approval.domain.ApprovalStatus;
import io.sentinelops.api.incident.domain.IncidentStatus;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalStore {

    private final JdbcClient jdbc;

    public ApprovalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProposalSnapshot> lockProposalIncident(UUID proposalId) {
        return proposalIncident(proposalId, true);
    }

    public Optional<ProposalSnapshot> lockProposalIncidentForDecision(UUID proposalId) {
        return proposalIncident(proposalId, true);
    }

    private Optional<ProposalSnapshot> proposalIncident(UUID proposalId, boolean lockIncident) {
        String locking = lockIncident ? " for update of i" : "";
        return jdbc.sql("""
                        select p.id as proposal_id, p.incident_id, p.proposal_hash, p.risk_level,
                               i.service_id, i.status as incident_status,
                               i.version as incident_version
                        from diagnosis_proposal p
                        join incident i on i.id = p.incident_id
                        where p.id = :proposalId
                        """ + locking)
                .param("proposalId", proposalId)
                .query(this::mapProposal)
                .optional();
    }

    public Optional<RequestSnapshot> lockRequest(UUID requestId) {
        return jdbc.sql("""
                        select id, incident_id, proposal_id, proposal_hash,
                               requester_principal_id, required_approvals,
                               independent_approver_required, status, resource_version,
                               created_at, expires_at, decided_at
                        from approval_request
                        where id = :requestId
                        for update
                        """)
                .param("requestId", requestId)
                .query(this::mapRequest)
                .optional();
    }

    public Instant databaseTime() {
        return jdbc.sql("select clock_timestamp()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    public void insertRequest(
            UUID id,
            ProposalSnapshot proposal,
            UUID requesterId,
            int requiredApprovals,
            boolean independentApproverRequired,
            Instant createdAt,
            Instant expiresAt) {
        jdbc.sql("""
                        insert into approval_request(
                          id, incident_id, proposal_id, proposal_hash,
                          requester_principal_id, policy_version, required_approvals,
                          independent_approver_required, status, resource_version,
                          created_at, expires_at
                        ) values (
                          :id, :incidentId, :proposalId, :proposalHash,
                          :requesterId, 'stage1-v1', :requiredApprovals,
                          :independent, 'pending', 0, :createdAt, :expiresAt
                        )
                        """)
                .param("id", id)
                .param("incidentId", proposal.incidentId())
                .param("proposalId", proposal.proposalId())
                .param("proposalHash", proposal.proposalHash())
                .param("requesterId", requesterId)
                .param("requiredApprovals", requiredApprovals)
                .param("independent", independentApproverRequired)
                .param("createdAt", timestamp(createdAt))
                .param("expiresAt", timestamp(expiresAt))
                .update();
    }

    public IncidentEventAllocation transitionToAwaitingApproval(
            ProposalSnapshot proposal, Instant occurredAt) {
        return jdbc.sql("""
                        update incident
                        set status = 'awaiting_approval',
                            version = version + 1,
                            next_event_seq = next_event_seq + 1,
                            updated_at = :occurredAt
                        where id = :incidentId
                          and status = 'diagnosed'
                          and version = :expectedVersion
                        returning version, next_event_seq - 1 as allocated_seq
                        """)
                .param("occurredAt", timestamp(occurredAt))
                .param("incidentId", proposal.incidentId())
                .param("expectedVersion", proposal.incidentVersion())
                .query((resultSet, rowNumber) -> new IncidentEventAllocation(
                        resultSet.getLong("version"), resultSet.getLong("allocated_seq")))
                .optional()
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "Incident changed while requesting approval"));
    }

    public long allocateIncidentEvent(UUID incidentId, Instant occurredAt) {
        return jdbc.sql("""
                        update incident
                        set next_event_seq = next_event_seq + 1,
                            updated_at = :occurredAt
                        where id = :incidentId
                        returning next_event_seq - 1
                        """)
                .param("occurredAt", timestamp(occurredAt))
                .param("incidentId", incidentId)
                .query(Long.class)
                .single();
    }

    public IncidentEventAllocation transitionToEscalated(
            UUID incidentId, long expectedVersion, Instant occurredAt) {
        return jdbc.sql("""
                        update incident
                        set status = 'escalated',
                            version = version + 1,
                            next_event_seq = next_event_seq + 1,
                            updated_at = :occurredAt
                        where id = :incidentId
                          and status = 'awaiting_approval'
                          and version = :expectedVersion
                        returning version, next_event_seq - 1 as allocated_seq
                        """)
                .param("occurredAt", timestamp(occurredAt))
                .param("incidentId", incidentId)
                .param("expectedVersion", expectedVersion)
                .query((resultSet, rowNumber) -> new IncidentEventAllocation(
                        resultSet.getLong("version"), resultSet.getLong("allocated_seq")))
                .optional()
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "Incident changed while closing approval"));
    }

    public void appendIncidentEvent(
            UUID eventId,
            UUID incidentId,
            long sequence,
            String eventType,
            String actorId,
            String payloadJson,
            Instant occurredAt) {
        jdbc.sql("""
                        insert into incident_event(
                          id, incident_id, seq_no, event_type, actor_type,
                          actor_id, payload, occurred_at
                        ) values (
                          :id, :incidentId, :sequence, :eventType, 'user',
                          :actorId, cast(:payload as jsonb), :occurredAt
                        )
                        """)
                .param("id", eventId)
                .param("incidentId", incidentId)
                .param("sequence", sequence)
                .param("eventType", eventType)
                .param("actorId", actorId)
                .param("payload", payloadJson)
                .param("occurredAt", timestamp(occurredAt))
                .update();
    }

    public boolean hasDecision(UUID requestId, UUID reviewerId) {
        return jdbc.sql("""
                        select exists(
                          select 1 from approval_decision
                          where approval_request_id = :requestId
                            and reviewer_principal_id = :reviewerId
                        )
                        """)
                .param("requestId", requestId)
                .param("reviewerId", reviewerId)
                .query(Boolean.class)
                .single();
    }

    public void insertDecision(
            UUID id,
            UUID requestId,
            UUID reviewerId,
            ApprovalDecision decision,
            String comment,
            String proposalHash,
            Instant decidedAt) {
        jdbc.sql("""
                        insert into approval_decision(
                          id, approval_request_id, reviewer_principal_id,
                          decision, comment, proposal_hash, decided_at
                        ) values (
                          :id, :requestId, :reviewerId,
                          :decision, :comment, :proposalHash, :decidedAt
                        )
                        """)
                .param("id", id)
                .param("requestId", requestId)
                .param("reviewerId", reviewerId)
                .param("decision", decision.databaseValue())
                .param("comment", comment)
                .param("proposalHash", proposalHash)
                .param("decidedAt", timestamp(decidedAt))
                .update();
    }

    public DecisionCounts countDecisions(UUID requestId) {
        return jdbc.sql("""
                        select count(*) filter (where decision = 'approve') as approvals,
                               count(*) filter (where decision = 'reject') as rejections
                        from approval_decision
                        where approval_request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNumber) -> new DecisionCounts(
                        resultSet.getInt("approvals"), resultSet.getInt("rejections")))
                .single();
    }

    public long updateAfterDecision(
            UUID requestId, ApprovalStatus status, Instant decidedAt) {
        boolean terminal = status != ApprovalStatus.PENDING;
        return jdbc.sql("""
                        update approval_request
                        set status = :status,
                            resource_version = resource_version + 1,
                            decided_at = case when :terminal then :decidedAt else null end
                        where id = :requestId and status = 'pending'
                        returning resource_version
                        """)
                .param("status", status.databaseValue())
                .param("terminal", terminal)
                .param("decidedAt", timestamp(decidedAt))
                .param("requestId", requestId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "Approval request changed while recording a decision"));
    }

    public long invalidate(UUID requestId, ApprovalStatus status, Instant decidedAt) {
        if (status != ApprovalStatus.EXPIRED && status != ApprovalStatus.INVALIDATED) {
            throw new IllegalArgumentException("Only expired or invalidated requests can be invalidated");
        }
        return updateAfterDecision(requestId, status, decidedAt);
    }

    private ProposalSnapshot mapProposal(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProposalSnapshot(
                resultSet.getObject("proposal_id", UUID.class),
                resultSet.getObject("incident_id", UUID.class),
                resultSet.getObject("service_id", UUID.class),
                resultSet.getString("proposal_hash"),
                RiskLevel.fromDatabase(resultSet.getString("risk_level")),
                IncidentStatus.fromDatabase(resultSet.getString("incident_status")),
                resultSet.getLong("incident_version"));
    }

    private RequestSnapshot mapRequest(ResultSet resultSet, int rowNumber) throws SQLException {
        var decidedAt = resultSet.getObject("decided_at", OffsetDateTime.class);
        return new RequestSnapshot(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("incident_id", UUID.class),
                resultSet.getObject("proposal_id", UUID.class),
                resultSet.getString("proposal_hash"),
                resultSet.getObject("requester_principal_id", UUID.class),
                resultSet.getInt("required_approvals"),
                resultSet.getBoolean("independent_approver_required"),
                ApprovalStatus.fromDatabase(resultSet.getString("status")),
                resultSet.getLong("resource_version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                decidedAt == null ? null : decidedAt.toInstant());
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record ProposalSnapshot(
            UUID proposalId,
            UUID incidentId,
            UUID serviceId,
            String proposalHash,
            RiskLevel riskLevel,
            IncidentStatus incidentStatus,
            long incidentVersion) {}

    public record RequestSnapshot(
            UUID id,
            UUID incidentId,
            UUID proposalId,
            String proposalHash,
            UUID requesterId,
            int requiredApprovals,
            boolean independentApproverRequired,
            ApprovalStatus status,
            long resourceVersion,
            Instant createdAt,
            Instant expiresAt,
            Instant decidedAt) {}

    public record IncidentEventAllocation(long incidentVersion, long sequence) {}

    public record DecisionCounts(int approvals, int rejections) {}
}
