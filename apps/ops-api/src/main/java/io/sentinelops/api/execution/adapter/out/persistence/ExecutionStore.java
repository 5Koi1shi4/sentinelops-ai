package io.sentinelops.api.execution.adapter.out.persistence;

import io.sentinelops.api.execution.domain.Execution;
import io.sentinelops.api.execution.domain.ExecutionStatus;
import io.sentinelops.api.incident.domain.IncidentStatus;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ExecutionStore {

    private static final TypeReference<Map<String, Object>> PARAMETERS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ExecutionStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<ApprovalSnapshot> lockApprovalForProposal(UUID proposalId) {
        return jdbc.sql("""
                        select id, incident_id, proposal_id, proposal_hash,
                               target_alias, status, expires_at
                        from approval_request
                        where proposal_id = :proposalId
                          and status in ('pending','approved')
                        order by created_at desc, id desc
                        limit 1
                        for update
                        """)
                .param("proposalId", proposalId)
                .query((resultSet, rowNumber) -> new ApprovalSnapshot(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("incident_id", UUID.class),
                        resultSet.getObject("proposal_id", UUID.class),
                        resultSet.getString("proposal_hash"),
                        resultSet.getString("target_alias"),
                        resultSet.getString("status"),
                        resultSet.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    public Optional<CreationContext> lockCreationContext(
            UUID incidentId, UUID proposalId) {
        return jdbc.sql("""
                        select p.id as proposal_id, p.incident_id, p.proposal_hash,
                               p.proposal_payload::text, p.risk_level,
                               p.runbook_version_id, rv.definition_checksum,
                               rv.adapter_id, rv.lifecycle,
                               i.service_id, i.status as incident_status,
                               i.version as incident_version,
                               nullif(btrim(s.execution_target_aliases ->> 'primary'), '') as target
                        from diagnosis_proposal p
                        join incident i on i.id = p.incident_id
                        join service_catalog s on s.id = i.service_id
                        join runbook_version rv on rv.id = p.runbook_version_id
                        where p.id = :proposalId and i.id = :incidentId
                        for update of i, s
                        """)
                .param("proposalId", proposalId)
                .param("incidentId", incidentId)
                .query(this::mapCreationContext)
                .optional();
    }

    public Instant databaseTime() {
        return jdbc.sql("select clock_timestamp()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    public Optional<Execution> findByBusinessKey(String businessKey, long incidentVersion) {
        return jdbc.sql("""
                        select id, incident_id, proposal_id, approval_request_id,
                               status, fencing_token, created_at
                        from execution
                        where idempotency_key = :businessKey
                        """)
                .param("businessKey", businessKey)
                .query((resultSet, rowNumber) -> mapExecution(
                        resultSet, incidentVersion))
                .optional();
    }

    public Optional<Execution> find(UUID executionId) {
        return jdbc.sql("""
                        select e.id, e.incident_id, e.proposal_id, e.approval_request_id,
                               e.status, e.fencing_token, e.created_at, i.version as incident_version
                        from execution e
                        join incident i on i.id = e.incident_id
                        where e.id = :executionId
                        """)
                .param("executionId", executionId)
                .query((resultSet, rowNumber) -> mapExecution(
                        resultSet, resultSet.getLong("incident_version")))
                .optional();
    }

    public void insertExecution(
            UUID executionId,
            ApprovalSnapshot approval,
            String businessKey,
            Instant createdAt) {
        jdbc.sql("""
                        insert into execution(
                          id, incident_id, proposal_id, approval_request_id,
                          status, idempotency_key, target_alias,
                          fencing_token, created_at, updated_at
                        ) values (
                          :id, :incidentId, :proposalId, :approvalId,
                          'pending', :businessKey, :targetAlias,
                          0, :createdAt, :createdAt
                        )
                        """)
                .param("id", executionId)
                .param("incidentId", approval.incidentId())
                .param("proposalId", approval.proposalId())
                .param("approvalId", approval.id())
                .param("businessKey", businessKey)
                .param("targetAlias", approval.targetAlias())
                .param("createdAt", timestamp(createdAt))
                .update();
    }

    public IncidentTransition transitionIncident(
            UUID incidentId,
            IncidentStatus expected,
            IncidentStatus target,
            long expectedVersion,
            Instant occurredAt) {
        return jdbc.sql("""
                        update incident
                        set status = :target,
                            version = version + 1,
                            next_event_seq = next_event_seq + 1,
                            updated_at = :occurredAt
                        where id = :incidentId
                          and status = :expected
                          and version = :expectedVersion
                        returning version, next_event_seq - 1 as allocated_seq
                        """)
                .param("target", target.databaseValue())
                .param("occurredAt", timestamp(occurredAt))
                .param("incidentId", incidentId)
                .param("expected", expected.databaseValue())
                .param("expectedVersion", expectedVersion)
                .query((resultSet, rowNumber) -> new IncidentTransition(
                        resultSet.getLong("version"), resultSet.getLong("allocated_seq")))
                .optional()
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "Incident changed while applying execution transition"));
    }

    public void appendIncidentEvent(
            UUID eventId,
            UUID incidentId,
            long sequence,
            String eventType,
            String actorType,
            String actorId,
            String payloadJson,
            Instant occurredAt) {
        jdbc.sql("""
                        insert into incident_event(
                          id, incident_id, seq_no, event_type, actor_type,
                          actor_id, payload, occurred_at
                        ) values (
                          :id, :incidentId, :sequence, :eventType, :actorType,
                          :actorId, cast(:payload as jsonb), :occurredAt
                        )
                        """)
                .param("id", eventId)
                .param("incidentId", incidentId)
                .param("sequence", sequence)
                .param("eventType", eventType)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("payload", payloadJson)
                .param("occurredAt", timestamp(occurredAt))
                .update();
    }

    @Transactional
    public ClaimOutcome claim(
            UUID executionId,
            String executorId,
            String ticketJti,
            String claimAttemptKey,
            UUID invalidationEventId) {
        var claimed = jdbc.sql("""
                        with locked_incident as materialized (
                          select i.id
                          from incident i
                          where i.id = (
                            select incident_id from execution where id = :executionId
                          )
                            and i.status = 'executing'
                          for update
                        )
                        update execution e
                        set status = 'running',
                            claimed_by = :executorId,
                            lease_until = clock_timestamp() + interval '30 seconds',
                            fencing_token = fencing_token + 1,
                            ticket_jti = :ticketJti,
                            claim_attempt_key = :claimAttemptKey,
                            ticket_issued_at = clock_timestamp(),
                            started_at = coalesce(started_at, clock_timestamp()),
                            updated_at = clock_timestamp()
                        from approval_request ar,
                             diagnosis_proposal p,
                             runbook_version rv,
                             locked_incident i
                        where e.id = :executionId
                          and e.status in ('pending','running')
                          and (e.status = 'pending' or e.lease_until < clock_timestamp())
                          and ar.id = e.approval_request_id
                          and ar.status = 'approved'
                          and ar.expires_at > clock_timestamp()
                          and p.id = e.proposal_id
                          and p.incident_id = e.incident_id
                          and p.proposal_hash = ar.proposal_hash
                          and e.target_alias = ar.target_alias
                          and rv.id = p.runbook_version_id
                          and rv.lifecycle = 'published'
                          and i.id = e.incident_id
                        returning e.id, e.incident_id, e.proposal_id,
                                  e.ticket_jti, e.ticket_issued_at,
                                  e.fencing_token, e.lease_until,
                                  rv.runbook_id, p.runbook_version_id,
                                  rv.definition_checksum, rv.adapter_id,
                                  rv.definition #>> '{steps,0,stepId}' as step_id,
                                  rv.definition #>> '{steps,0,operation}' as operation,
                                  p.proposal_payload -> 'parameters' as parameters,
                                  e.target_alias as target,
                                  p.risk_level
                        """)
                .param("executorId", executorId)
                .param("executionId", executionId)
                .param("ticketJti", ticketJti)
                .param("claimAttemptKey", claimAttemptKey)
                .query(this::mapClaimLease)
                .optional();
        if (claimed.isPresent()) {
            return ClaimOutcome.claimed(claimed.orElseThrow());
        }

        var context = lockClaimContext(executionId);
        if (context.isEmpty()) {
            return ClaimOutcome.unavailable();
        }
        var locked = context.orElseThrow();
        if (leaseIsActive(locked)) {
            return ClaimOutcome.activeLeaseRejected();
        }
        String invalidationReason = authorizationInvalidationReason(locked);
        if (invalidationReason == null) {
            return ClaimOutcome.unavailable();
        }
        if (locked.authorizationInvalidationRecorded()) {
            return ClaimOutcome.invalidatedAuthorization();
        }
        if (locked.incidentStatus() != IncidentStatus.EXECUTING
                || !leaseCanBeReclaimed(locked)) {
            return ClaimOutcome.unavailable();
        }

        Instant invalidatedAt = locked.databaseNow();
        int updated = jdbc.sql("""
                        update execution
                        set status = 'escalated',
                            claimed_by = null,
                            lease_until = null,
                            completed_at = :invalidatedAt,
                            updated_at = :invalidatedAt
                        where id = :executionId
                          and status = :expectedStatus
                          and fencing_token = :fencingToken
                        """)
                .param("invalidatedAt", timestamp(invalidatedAt))
                .param("executionId", executionId)
                .param("expectedStatus", locked.executionStatus().databaseValue())
                .param("fencingToken", locked.fencingToken())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                    "Execution changed while invalidating its authorization");
        }
        var transition = transitionIncident(
                locked.incidentId(),
                locked.incidentStatus(),
                IncidentStatus.ESCALATED,
                locked.incidentVersion(),
                invalidatedAt);
        var payload = objectMapper.createObjectNode()
                .put("executionId", executionId.toString())
                .put("reason", invalidationReason);
        appendIncidentEvent(
                invalidationEventId,
                locked.incidentId(),
                transition.sequence(),
                "execution_authorization_invalidated",
                "system",
                "sentinelops-api",
                objectMapper.writeValueAsString(payload),
                invalidatedAt);
        return ClaimOutcome.invalidatedAuthorization();
    }

    private Optional<ClaimContext> lockClaimContext(UUID executionId) {
        var incidentId = jdbc.sql("select incident_id from execution where id = :executionId")
                .param("executionId", executionId)
                .query(UUID.class)
                .optional();
        if (incidentId.isEmpty()) {
            return Optional.empty();
        }
        var incident = jdbc.sql("""
                        select status, version
                        from incident
                        where id = :incidentId
                        for update
                        """)
                .param("incidentId", incidentId.orElseThrow())
                .query((resultSet, rowNumber) -> new IncidentLease(
                        IncidentStatus.fromDatabase(resultSet.getString("status")),
                        resultSet.getLong("version")))
                .single();
        return jdbc.sql("""
                        select e.id, e.incident_id, e.status, e.lease_until,
                               e.fencing_token, ar.status as approval_status,
                               ar.expires_at, p.proposal_hash = ar.proposal_hash
                                 as proposal_matches,
                               e.target_alias = ar.target_alias as target_matches,
                               rv.lifecycle as runbook_lifecycle,
                               clock_timestamp() as database_now,
                               exists (
                                 select 1
                                 from incident_event ie
                                 where ie.incident_id = e.incident_id
                                   and ie.event_type = 'execution_authorization_invalidated'
                                   and ie.payload ->> 'executionId' = e.id::text
                               ) as invalidation_recorded
                        from execution e
                        join approval_request ar on ar.id = e.approval_request_id
                        join diagnosis_proposal p on p.id = e.proposal_id
                        join runbook_version rv on rv.id = p.runbook_version_id
                        where e.id = :executionId
                        for update of e
                        """)
                .param("executionId", executionId)
                .query((resultSet, rowNumber) -> {
                    var leaseUntil = resultSet.getObject("lease_until", OffsetDateTime.class);
                    return new ClaimContext(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getObject("incident_id", UUID.class),
                            ExecutionStatus.fromDatabase(resultSet.getString("status")),
                            leaseUntil == null ? null : leaseUntil.toInstant(),
                            resultSet.getLong("fencing_token"),
                            resultSet.getString("approval_status"),
                            resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                            resultSet.getBoolean("proposal_matches"),
                            resultSet.getBoolean("target_matches"),
                            resultSet.getString("runbook_lifecycle"),
                            resultSet.getObject("database_now", OffsetDateTime.class).toInstant(),
                            resultSet.getBoolean("invalidation_recorded"),
                            incident.status(),
                            incident.version());
                })
                .optional();
    }

    private String authorizationInvalidationReason(ClaimContext context) {
        if (!"approved".equals(context.approvalStatus())) {
            return "approval_not_approved";
        }
        if (!context.approvalExpiresAt().isAfter(context.databaseNow())) {
            return "approval_expired";
        }
        if (!context.proposalMatchesApproval()) {
            return "proposal_changed";
        }
        if (!context.targetMatchesApproval()) {
            return "target_changed";
        }
        if (!"published".equals(context.runbookLifecycle())) {
            return "runbook_not_published";
        }
        return null;
    }

    private boolean leaseCanBeReclaimed(ClaimContext context) {
        return context.executionStatus() == ExecutionStatus.PENDING
                || (context.executionStatus() == ExecutionStatus.RUNNING
                        && context.leaseUntil() != null
                        && context.leaseUntil().isBefore(context.databaseNow()));
    }

    private boolean leaseIsActive(ClaimContext context) {
        return context.executionStatus() == ExecutionStatus.RUNNING
                && context.leaseUntil() != null
                && !context.leaseUntil().isBefore(context.databaseNow());
    }

    @Transactional
    public Optional<ClaimLease> recoverClaim(
            UUID executionId, String executorId, String claimAttemptKey) {
        var incidentId = jdbc.sql("select incident_id from execution where id = :executionId")
                .param("executionId", executionId)
                .query(UUID.class)
                .optional();
        if (incidentId.isEmpty()) {
            return Optional.empty();
        }
        var incidentStatus = jdbc.sql("""
                        select status
                        from incident
                        where id = :incidentId
                        for update
                        """)
                .param("incidentId", incidentId.orElseThrow())
                .query(String.class)
                .single();
        if (!"executing".equals(incidentStatus)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select e.id, e.incident_id, e.proposal_id,
                               e.ticket_jti, e.ticket_issued_at,
                               e.fencing_token, e.lease_until,
                               rv.runbook_id, p.runbook_version_id,
                               rv.definition_checksum, rv.adapter_id,
                               rv.definition #>> '{steps,0,stepId}' as step_id,
                               rv.definition #>> '{steps,0,operation}' as operation,
                               p.proposal_payload -> 'parameters' as parameters,
                               e.target_alias as target, p.risk_level
                        from execution e
                        join approval_request ar on ar.id = e.approval_request_id
                        join diagnosis_proposal p on p.id = e.proposal_id
                        join runbook_version rv on rv.id = p.runbook_version_id
                        where e.id = :executionId
                          and e.status = 'running'
                          and e.claimed_by = :executorId
                          and e.claim_attempt_key = :claimAttemptKey
                          and e.lease_until > clock_timestamp()
                          and e.ticket_jti is not null
                          and e.ticket_issued_at is not null
                          and e.target_alias = ar.target_alias
                          and p.proposal_hash = ar.proposal_hash
                        for update of e
                        """)
                .param("executionId", executionId)
                .param("executorId", executorId)
                .param("claimAttemptKey", claimAttemptKey)
                .query(this::mapClaimLease)
                .optional();
    }

    public Optional<ClaimLease> heartbeat(
            UUID executionId,
            String executorId,
            long fencingToken,
            String ticketJti,
            String newTicketJti,
            String runbookChecksum) {
        return jdbc.sql("""
                        update execution e
                        set lease_until = clock_timestamp() + interval '30 seconds',
                            ticket_jti = :newTicketJti,
                            ticket_issued_at = clock_timestamp(),
                            updated_at = clock_timestamp()
                        from diagnosis_proposal p,
                             runbook_version rv
                        where e.id = :executionId
                          and e.status = 'running'
                          and e.claimed_by = :executorId
                          and e.fencing_token = :fencingToken
                          and e.ticket_jti = :ticketJti
                          and e.lease_until > clock_timestamp()
                          and p.id = e.proposal_id
                          and rv.id = p.runbook_version_id
                          and rv.definition_checksum = :runbookChecksum
                        returning e.id, e.incident_id, e.proposal_id,
                                  e.ticket_jti, e.ticket_issued_at,
                                  e.fencing_token, e.lease_until,
                                  rv.runbook_id, p.runbook_version_id,
                                  rv.definition_checksum, rv.adapter_id,
                                  rv.definition #>> '{steps,0,stepId}' as step_id,
                                  rv.definition #>> '{steps,0,operation}' as operation,
                                  p.proposal_payload -> 'parameters' as parameters,
                                  e.target_alias as target,
                                  p.risk_level
                        """)
                .param("executionId", executionId)
                .param("executorId", executorId)
                .param("fencingToken", fencingToken)
                .param("ticketJti", ticketJti)
                .param("newTicketJti", newTicketJti)
                .param("runbookChecksum", runbookChecksum)
                .query(this::mapClaimLease)
                .optional();
    }

    public Optional<LeaseSnapshot> lockLease(UUID executionId) {
        var incidentId = jdbc.sql("""
                        select incident_id
                        from execution
                        where id = :executionId
                        """)
                .param("executionId", executionId)
                .query(UUID.class)
                .optional();
        if (incidentId.isEmpty()) {
            return Optional.empty();
        }
        var incident = jdbc.sql("""
                        select status, version
                        from incident
                        where id = :incidentId
                        for update
                        """)
                .param("incidentId", incidentId.orElseThrow())
                .query((resultSet, rowNumber) -> new IncidentLease(
                        IncidentStatus.fromDatabase(resultSet.getString("status")),
                        resultSet.getLong("version")))
                .single();
        var execution = jdbc.sql("""
                        select e.id, e.incident_id, e.status, e.claimed_by,
                               e.lease_until, e.fencing_token, e.ticket_jti,
                               rv.definition_checksum
                        from execution e
                        join diagnosis_proposal p on p.id = e.proposal_id
                        join runbook_version rv on rv.id = p.runbook_version_id
                        where e.id = :executionId
                        for update of e
                        """)
                .param("executionId", executionId)
                .query(this::mapExecutionLease)
                .optional();
        if (execution.isEmpty()) {
            return Optional.empty();
        }
        var lockedExecution = execution.orElseThrow();
        return Optional.of(new LeaseSnapshot(
                lockedExecution.executionId(),
                lockedExecution.incidentId(),
                lockedExecution.status(),
                lockedExecution.claimedBy(),
                lockedExecution.leaseUntil(),
                lockedExecution.fencingToken(),
                lockedExecution.ticketJti(),
                lockedExecution.runbookChecksum(),
                incident.version(),
                incident.status()));
    }

    public void insertAttempt(
            UUID attemptId,
            UUID executionId,
            String stepId,
            int attemptNo,
            long fencingToken,
            String adapterId,
            String adapterVersion,
            String requestHash,
            String outcome,
            String sanitizedResultJson,
            Instant completedAt) {
        jdbc.sql("""
                        insert into execution_attempt(
                          id, execution_id, step_id, attempt_no, fencing_token,
                          adapter_id, adapter_version, request_hash, outcome,
                          sanitized_result, started_at, completed_at
                        ) values (
                          :id, :executionId, :stepId, :attemptNo, :fencingToken,
                          :adapterId, :adapterVersion, :requestHash, :outcome,
                          cast(:result as jsonb), :completedAt, :completedAt
                        )
                        """)
                .param("id", attemptId)
                .param("executionId", executionId)
                .param("stepId", stepId)
                .param("attemptNo", attemptNo)
                .param("fencingToken", fencingToken)
                .param("adapterId", adapterId)
                .param("adapterVersion", adapterVersion)
                .param("requestHash", requestHash)
                .param("outcome", outcome)
                .param("result", sanitizedResultJson)
                .param("completedAt", timestamp(completedAt))
                .update();
    }

    public void updateAfterAttempt(
            UUID executionId,
            ExecutionStatus status,
            String executorId,
            long fencingToken,
            Instant updatedAt) {
        int updated = jdbc.sql("""
                        update execution
                        set status = :status,
                            claimed_by = null,
                            lease_until = null,
                            updated_at = :updatedAt,
                            completed_at = case when :terminal then :updatedAt else completed_at end
                        where id = :executionId
                          and status = 'running'
                          and claimed_by = :executorId
                          and fencing_token = :fencingToken
                        """)
                .param("status", status.databaseValue())
                .param("terminal", status == ExecutionStatus.FAILED)
                .param("updatedAt", timestamp(updatedAt))
                .param("executionId", executionId)
                .param("executorId", executorId)
                .param("fencingToken", fencingToken)
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                    "Execution changed while recording its attempt");
        }
    }

    private CreationContext mapCreationContext(ResultSet resultSet, int rowNumber)
            throws SQLException {
        var payload = objectMapper.readTree(resultSet.getString("proposal_payload"));
        return new CreationContext(
                resultSet.getObject("proposal_id", UUID.class),
                resultSet.getObject("incident_id", UUID.class),
                resultSet.getObject("service_id", UUID.class),
                resultSet.getString("proposal_hash"),
                RiskLevel.fromDatabase(resultSet.getString("risk_level")),
                resultSet.getObject("runbook_version_id", UUID.class),
                resultSet.getString("definition_checksum"),
                resultSet.getString("adapter_id"),
                resultSet.getString("lifecycle"),
                objectMapper.convertValue(payload.path("parameters"), PARAMETERS),
                resultSet.getString("target"),
                IncidentStatus.fromDatabase(resultSet.getString("incident_status")),
                resultSet.getLong("incident_version"));
    }

    private ClaimLease mapClaimLease(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new ClaimLease(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("incident_id", UUID.class),
                resultSet.getObject("proposal_id", UUID.class),
                resultSet.getObject("runbook_id", UUID.class),
                resultSet.getObject("runbook_version_id", UUID.class),
                resultSet.getString("definition_checksum"),
                objectMapper.readValue(resultSet.getString("parameters"), PARAMETERS),
                resultSet.getString("target"),
                RiskLevel.fromDatabase(resultSet.getString("risk_level")),
                resultSet.getString("adapter_id"),
                resultSet.getString("step_id"),
                resultSet.getString("operation"),
                resultSet.getLong("fencing_token"),
                resultSet.getObject("lease_until", OffsetDateTime.class).toInstant(),
                resultSet.getString("ticket_jti"),
                resultSet.getObject("ticket_issued_at", OffsetDateTime.class).toInstant());
    }

    private Execution mapExecution(ResultSet resultSet, long incidentVersion)
            throws SQLException {
        return new Execution(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("incident_id", UUID.class),
                resultSet.getObject("proposal_id", UUID.class),
                resultSet.getObject("approval_request_id", UUID.class),
                ExecutionStatus.fromDatabase(resultSet.getString("status")),
                resultSet.getLong("fencing_token"),
                incidentVersion,
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private ExecutionLease mapExecutionLease(ResultSet resultSet, int rowNumber)
            throws SQLException {
        var leaseUntil = resultSet.getObject("lease_until", OffsetDateTime.class);
        return new ExecutionLease(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("incident_id", UUID.class),
                ExecutionStatus.fromDatabase(resultSet.getString("status")),
                resultSet.getString("claimed_by"),
                leaseUntil == null ? null : leaseUntil.toInstant(),
                resultSet.getLong("fencing_token"),
                resultSet.getString("ticket_jti"),
                resultSet.getString("definition_checksum"));
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record ApprovalSnapshot(
            UUID id,
            UUID incidentId,
            UUID proposalId,
            String proposalHash,
            String targetAlias,
            String status,
            Instant expiresAt) {}

    public record CreationContext(
            UUID proposalId,
            UUID incidentId,
            UUID serviceId,
            String proposalHash,
            RiskLevel risk,
            UUID runbookVersionId,
            String runbookChecksum,
            String adapterId,
            String runbookLifecycle,
            Map<String, Object> parameters,
            String target,
            IncidentStatus incidentStatus,
            long incidentVersion) {}

    public record IncidentTransition(long incidentVersion, long sequence) {}

    public record ClaimLease(
            UUID executionId,
            UUID incidentId,
            UUID proposalId,
            UUID runbookId,
            UUID runbookVersionId,
            String runbookChecksum,
            Map<String, Object> parameters,
            String target,
            RiskLevel risk,
            String adapterId,
            String stepId,
            String operation,
            long fencingToken,
            Instant leaseUntil,
            String ticketJti,
            Instant ticketIssuedAt) {}

    public record ClaimOutcome(
            ClaimLease lease, boolean authorizationInvalidated, boolean activeLease) {

        private static ClaimOutcome claimed(ClaimLease lease) {
            return new ClaimOutcome(lease, false, false);
        }

        private static ClaimOutcome invalidatedAuthorization() {
            return new ClaimOutcome(null, true, false);
        }

        private static ClaimOutcome activeLeaseRejected() {
            return new ClaimOutcome(null, false, true);
        }

        private static ClaimOutcome unavailable() {
            return new ClaimOutcome(null, false, false);
        }
    }

    public record LeaseSnapshot(
            UUID executionId,
            UUID incidentId,
            ExecutionStatus status,
            String claimedBy,
            Instant leaseUntil,
            long fencingToken,
            String ticketJti,
            String runbookChecksum,
            long incidentVersion,
            IncidentStatus incidentStatus) {}

    private record ExecutionLease(
            UUID executionId,
            UUID incidentId,
            ExecutionStatus status,
            String claimedBy,
            Instant leaseUntil,
            long fencingToken,
            String ticketJti,
            String runbookChecksum) {}

    private record IncidentLease(IncidentStatus status, long version) {}

    private record ClaimContext(
            UUID executionId,
            UUID incidentId,
            ExecutionStatus executionStatus,
            Instant leaseUntil,
            long fencingToken,
            String approvalStatus,
            Instant approvalExpiresAt,
            boolean proposalMatchesApproval,
            boolean targetMatchesApproval,
            String runbookLifecycle,
            Instant databaseNow,
            boolean authorizationInvalidationRecorded,
            IncidentStatus incidentStatus,
            long incidentVersion) {}
}
