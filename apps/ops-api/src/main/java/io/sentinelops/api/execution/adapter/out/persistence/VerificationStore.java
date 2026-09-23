package io.sentinelops.api.execution.adapter.out.persistence;

import io.sentinelops.api.incident.domain.IncidentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VerificationStore {

    private final JdbcClient jdbc;

    public VerificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Instant databaseTime() {
        return jdbc.sql("select clock_timestamp()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    public Optional<ManualContext> lockManualContext(UUID incidentId) {
        return jdbc.sql("""
                        select i.id, i.service_id, i.status, i.version,
                               rv.id as runbook_version_id,
                               rv.lifecycle as runbook_lifecycle,
                               rv.definition #>> '{verification,probe}' as probe,
                               cast(rv.definition #>> '{verification,successThreshold}'
                                 as numeric) as success_threshold,
                               cast(rv.definition #>> '{verification,attempts}'
                                 as integer) as max_attempts,
                               cast(rv.definition #>> '{verification,intervalSeconds}'
                                 as integer) as interval_seconds,
                               nullif(btrim(s.execution_target_aliases ->> 'primary'), '')
                                 as target_alias
                        from incident i
                        join service_catalog s on s.id = i.service_id
                        left join lateral (
                          select candidate.runbook_version_id
                          from diagnosis_proposal candidate
                          where candidate.incident_id = i.id
                          order by candidate.created_at desc, candidate.id desc
                          limit 1
                        ) p on true
                        left join runbook_version rv on rv.id = p.runbook_version_id
                        where i.id = :incidentId
                        for update of i
                        """)
                .param("incidentId", incidentId)
                .query((resultSet, rowNumber) -> {
                    UUID runbookVersionId =
                            resultSet.getObject("runbook_version_id", UUID.class);
                    VerificationPolicy policy = null;
                    if (runbookVersionId != null
                            && "published".equals(resultSet.getString("runbook_lifecycle"))
                            && resultSet.getString("probe") != null
                            && resultSet.getString("target_alias") != null) {
                        policy = new VerificationPolicy(
                                runbookVersionId,
                                resultSet.getString("probe"),
                                resultSet.getString("target_alias"),
                                resultSet.getBigDecimal("success_threshold"),
                                resultSet.getInt("max_attempts"),
                                resultSet.getInt("interval_seconds"));
                    }
                    return new ManualContext(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getObject("service_id", UUID.class),
                            IncidentStatus.fromDatabase(resultSet.getString("status")),
                            resultSet.getLong("version"),
                            policy);
                })
                .optional();
    }

    public Transition transitionForManualVerification(
            ManualContext context, Instant occurredAt) {
        return jdbc.sql("""
                        update incident
                        set status = 'verifying',
                            version = version + 1,
                            next_event_seq = next_event_seq + 1,
                            updated_at = :occurredAt
                        where id = :incidentId
                          and status = :expectedStatus
                          and version = :expectedVersion
                        returning version, next_event_seq - 1 as allocated_seq
                        """)
                .param("occurredAt", timestamp(occurredAt))
                .param("incidentId", context.incidentId())
                .param("expectedStatus", context.status().databaseValue())
                .param("expectedVersion", context.version())
                .query((resultSet, rowNumber) -> new Transition(
                        resultSet.getLong("version"),
                        resultSet.getLong("allocated_seq")))
                .optional()
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "Incident changed while starting manual verification"));
    }

    public void enqueueManualCycle(
            UUID cycleId,
            ManualContext context,
            long incidentVersion,
            Instant startedAt) {
        var policy = Optional.ofNullable(context.policy()).orElseThrow(() ->
                new IllegalStateException("Manual verification policy was not bound"));
        insertCycle(
                cycleId,
                context.incidentId(),
                incidentVersion,
                null,
                policy,
                startedAt);
    }

    public void enqueueExecutionCycle(
            UUID cycleId,
            UUID executionId,
            long incidentVersion,
            Instant startedAt) {
        int inserted = jdbc.sql("""
                        insert into verification_cycle(
                          id, incident_id, incident_version, execution_id,
                          runbook_version_id, cycle_no, probe, target_alias,
                          success_threshold, max_attempts, interval_seconds,
                          status, started_at
                        )
                        select :cycleId, e.incident_id, :incidentVersion, e.id,
                               p.runbook_version_id,
                               coalesce((
                                 select max(existing.cycle_no) + 1
                                 from verification_cycle existing
                                 where existing.incident_id = e.incident_id
                               ), 1),
                               rv.definition #>> '{verification,probe}',
                               e.target_alias,
                               cast(rv.definition #>> '{verification,successThreshold}'
                                 as numeric),
                               cast(rv.definition #>> '{verification,attempts}'
                                 as integer),
                               cast(rv.definition #>> '{verification,intervalSeconds}'
                                 as integer),
                               'running', :startedAt
                        from execution e
                        join diagnosis_proposal p on p.id = e.proposal_id
                        join runbook_version rv on rv.id = p.runbook_version_id
                        where e.id = :executionId
                          and jsonb_typeof(rv.definition -> 'verification') = 'object'
                        """)
                .param("cycleId", cycleId)
                .param("incidentVersion", incidentVersion)
                .param("startedAt", timestamp(startedAt))
                .param("executionId", executionId)
                .update();
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Execution has no bound objective verification policy");
        }
    }

    private void insertCycle(
            UUID cycleId,
            UUID incidentId,
            long incidentVersion,
            UUID executionId,
            VerificationPolicy policy,
            Instant startedAt) {
        jdbc.sql("""
                        insert into verification_cycle(
                          id, incident_id, incident_version, execution_id,
                          runbook_version_id, cycle_no, probe, target_alias,
                          success_threshold, max_attempts, interval_seconds,
                          status, started_at
                        ) values (
                          :cycleId, :incidentId, :incidentVersion, :executionId,
                          :runbookVersionId,
                          coalesce((
                            select max(existing.cycle_no) + 1
                            from verification_cycle existing
                            where existing.incident_id = :incidentId
                          ), 1),
                          :probe, :targetAlias, :successThreshold,
                          :maxAttempts, :intervalSeconds, 'running', :startedAt
                        )
                        """)
                .param("cycleId", cycleId)
                .param("incidentId", incidentId)
                .param("incidentVersion", incidentVersion)
                .param("executionId", executionId)
                .param("runbookVersionId", policy.runbookVersionId())
                .param("probe", policy.probe())
                .param("targetAlias", policy.targetAlias())
                .param("successThreshold", policy.successThreshold())
                .param("maxAttempts", policy.maxAttempts())
                .param("intervalSeconds", policy.intervalSeconds())
                .param("startedAt", timestamp(startedAt))
                .update();
    }

    public void appendEvent(
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
    public Optional<ClaimedCycle> claimNext(
            UUID claimToken, String workerId, long leaseSeconds) {
        var claimed = jdbc.sql("""
                        with candidate as (
                          select id
                          from verification_cycle
                          where status = 'running'
                            and (claimed_by is null
                              or claim_until < clock_timestamp())
                          order by started_at, id
                          limit 1
                          for update skip locked
                        )
                        update verification_cycle vc
                        set claimed_by = :workerId,
                            claim_token = :claimToken,
                            claim_until = clock_timestamp()
                              + make_interval(secs => :leaseSeconds)
                        from candidate c
                        where vc.id = c.id
                        returning vc.id
                        """)
                .param("workerId", workerId)
                .param("claimToken", claimToken)
                .param("leaseSeconds", leaseSeconds)
                .query(UUID.class)
                .optional();
        return claimed.flatMap(this::loadClaimed);
    }

    private Optional<ClaimedCycle> loadClaimed(UUID cycleId) {
        return jdbc.sql("""
                        select vc.id, vc.incident_id, vc.incident_version,
                               vc.execution_id, vc.runbook_version_id, vc.cycle_no,
                               vc.probe, vc.target_alias, vc.success_threshold,
                               vc.max_attempts, vc.interval_seconds,
                               vc.claim_token,
                               count(va.id) as completed_attempts,
                               coalesce(bool_or(va.successful), false) as already_succeeded
                        from verification_cycle vc
                        left join verification_attempt va on va.cycle_id = vc.id
                        where vc.id = :cycleId
                        group by vc.id
                        """)
                .param("cycleId", cycleId)
                .query((resultSet, rowNumber) -> new ClaimedCycle(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("incident_id", UUID.class),
                        resultSet.getLong("incident_version"),
                        resultSet.getObject("execution_id", UUID.class),
                        resultSet.getObject("runbook_version_id", UUID.class),
                        resultSet.getInt("cycle_no"),
                        resultSet.getString("probe"),
                        resultSet.getString("target_alias"),
                        resultSet.getBigDecimal("success_threshold"),
                        resultSet.getInt("max_attempts"),
                        resultSet.getInt("interval_seconds"),
                        resultSet.getObject("claim_token", UUID.class),
                        resultSet.getInt("completed_attempts"),
                        resultSet.getBoolean("already_succeeded")))
                .optional();
    }

    @Transactional
    public void recordAttempt(
            UUID attemptId,
            UUID cycleId,
            UUID claimToken,
            String workerId,
            int attemptNo,
            boolean successful,
            String sanitizedResultJson,
            long leaseSeconds) {
        int inserted = jdbc.sql("""
                        insert into verification_attempt(
                          id, cycle_id, attempt_no, successful,
                          sanitized_result, observed_at
                        ) select :id, vc.id, :attemptNo, :successful,
                                 cast(:result as jsonb), clock_timestamp()
                        from verification_cycle vc
                        where vc.id = :cycleId
                          and vc.status = 'running'
                          and vc.claimed_by = :workerId
                          and vc.claim_token = :claimToken
                          and vc.claim_until > clock_timestamp()
                        """)
                .param("id", attemptId)
                .param("cycleId", cycleId)
                .param("workerId", workerId)
                .param("claimToken", claimToken)
                .param("attemptNo", attemptNo)
                .param("successful", successful)
                .param("result", sanitizedResultJson)
                .update();
        if (inserted != 1) {
            throw new OptimisticLockingFailureException(
                    "Verification cycle lease changed while recording an attempt");
        }
        int renewed = jdbc.sql("""
                        update verification_cycle
                        set claim_until = clock_timestamp()
                          + make_interval(secs => :leaseSeconds)
                        where id = :cycleId
                          and status = 'running'
                          and claimed_by = :workerId
                          and claim_token = :claimToken
                        """)
                .param("leaseSeconds", leaseSeconds)
                .param("cycleId", cycleId)
                .param("workerId", workerId)
                .param("claimToken", claimToken)
                .update();
        if (renewed != 1) {
            throw new OptimisticLockingFailureException(
                    "Verification cycle lease changed while renewing an attempt");
        }
    }

    @Transactional
    public Finalization finalizeCycle(
            UUID cycleId,
            UUID claimToken,
            String workerId,
            boolean successful,
            UUID eventId,
            UUID replacementCycleId,
            String eventPayloadJson) {
        var cycle = jdbc.sql("""
                        select id, incident_id, incident_version, execution_id,
                               cycle_no, probe
                        from verification_cycle
                        where id = :cycleId
                          and status = 'running'
                          and claimed_by = :workerId
                          and claim_token = :claimToken
                          and claim_until > clock_timestamp()
                        for update
                        """)
                .param("cycleId", cycleId)
                .param("workerId", workerId)
                .param("claimToken", claimToken)
                .query((resultSet, rowNumber) -> new FinalizationContext(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("incident_id", UUID.class),
                        resultSet.getLong("incident_version"),
                        resultSet.getObject("execution_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "Verification cycle lease is stale"));
        var incident = jdbc.sql("""
                        select status, version
                        from incident
                        where id = :incidentId
                        for update
                        """)
                .param("incidentId", cycle.incidentId())
                .query((resultSet, rowNumber) -> new IncidentVersion(
                        IncidentStatus.fromDatabase(resultSet.getString("status")),
                        resultSet.getLong("version")))
                .single();
        Instant completedAt = jdbc.sql("select clock_timestamp()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        if (incident.status() != IncidentStatus.VERIFYING
                || incident.version() != cycle.incidentVersion()) {
            completeCycle(cycleId, "superseded", completedAt);
            if (incident.status() == IncidentStatus.VERIFYING) {
                enqueueReplacementCycle(
                        cycleId, replacementCycleId, incident.version(), completedAt);
            }
            return new Finalization(false, incident.status(), incident.version());
        }

        int priorFailedCycles = jdbc.sql("""
                        select count(*)
                        from verification_cycle
                        where incident_id = :incidentId
                          and status = 'failed'
                        """)
                .param("incidentId", cycle.incidentId())
                .query(Integer.class)
                .single();
        IncidentStatus target = successful
                ? IncidentStatus.RESOLVED
                : priorFailedCycles == 0
                        ? IncidentStatus.TRIAGING
                        : IncidentStatus.ESCALATED;
        var transition = jdbc.sql("""
                        update incident
                        set status = :target,
                            version = version + 1,
                            next_event_seq = next_event_seq + 1,
                            updated_at = :completedAt,
                            resolved_at = case
                              when :resolved then :completedAt
                              else resolved_at
                            end
                        where id = :incidentId
                          and status = 'verifying'
                          and version = :expectedVersion
                        returning version, next_event_seq - 1 as allocated_seq
                        """)
                .param("target", target.databaseValue())
                .param("resolved", target == IncidentStatus.RESOLVED)
                .param("completedAt", timestamp(completedAt))
                .param("incidentId", cycle.incidentId())
                .param("expectedVersion", cycle.incidentVersion())
                .query((resultSet, rowNumber) -> new Transition(
                        resultSet.getLong("version"),
                        resultSet.getLong("allocated_seq")))
                .optional()
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "Incident changed while finalizing verification"));
        if (cycle.executionId() != null) {
            jdbc.sql("""
                            update execution
                            set status = :status,
                                updated_at = :completedAt,
                                completed_at = :completedAt
                            where id = :executionId and status = 'verifying'
                            """)
                    .param("status", successful ? "succeeded" : "unknown")
                    .param("completedAt", timestamp(completedAt))
                    .param("executionId", cycle.executionId())
                    .update();
        }
        completeCycle(cycleId, successful ? "succeeded" : "failed", completedAt);
        appendEvent(
                eventId,
                cycle.incidentId(),
                transition.sequence(),
                successful ? "verification_succeeded" : "verification_failed",
                "system",
                "sentinelops-verifier",
                eventPayloadJson,
                completedAt);
        return new Finalization(true, target, transition.version());
    }

    private void completeCycle(UUID cycleId, String status, Instant completedAt) {
        jdbc.sql("""
                        update verification_cycle
                        set status = :status,
                            claimed_by = null,
                            claim_token = null,
                            claim_until = null,
                            completed_at = :completedAt
                        where id = :cycleId and status = 'running'
                        """)
                .param("status", status)
                .param("completedAt", timestamp(completedAt))
                .param("cycleId", cycleId)
                .update();
    }

    private void enqueueReplacementCycle(
            UUID sourceCycleId,
            UUID replacementCycleId,
            long incidentVersion,
            Instant startedAt) {
        jdbc.sql("""
                        insert into verification_cycle(
                          id, incident_id, incident_version, execution_id,
                          runbook_version_id, cycle_no, probe, target_alias,
                          success_threshold, max_attempts, interval_seconds,
                          status, started_at
                        )
                        select :replacementCycleId, source.incident_id,
                               :incidentVersion, source.execution_id,
                               source.runbook_version_id,
                               coalesce((
                                 select max(existing.cycle_no) + 1
                                 from verification_cycle existing
                                 where existing.incident_id = source.incident_id
                               ), 1),
                               source.probe, source.target_alias,
                               source.success_threshold, source.max_attempts,
                               source.interval_seconds, 'running', :startedAt
                        from verification_cycle source
                        where source.id = :sourceCycleId
                        """)
                .param("replacementCycleId", replacementCycleId)
                .param("incidentVersion", incidentVersion)
                .param("startedAt", timestamp(startedAt))
                .param("sourceCycleId", sourceCycleId)
                .update();
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record ManualContext(
            UUID incidentId,
            UUID serviceId,
            IncidentStatus status,
            long version,
            VerificationPolicy policy) {

        public boolean hasVerificationPolicy() {
            return policy != null;
        }
    }

    public record VerificationPolicy(
            UUID runbookVersionId,
            String probe,
            String targetAlias,
            BigDecimal successThreshold,
            int maxAttempts,
            int intervalSeconds) {}

    public record Transition(long version, long sequence) {}

    public record ClaimedCycle(
            UUID id,
            UUID incidentId,
            long incidentVersion,
            UUID executionId,
            UUID runbookVersionId,
            int cycleNo,
            String probe,
            String targetAlias,
            BigDecimal successThreshold,
            int maxAttempts,
            int intervalSeconds,
            UUID claimToken,
            int completedAttempts,
            boolean alreadySucceeded) {}

    public record Finalization(boolean applied, IncidentStatus status, long incidentVersion) {}

    private record FinalizationContext(
            UUID id,
            UUID incidentId,
            long incidentVersion,
            UUID executionId) {}

    private record IncidentVersion(IncidentStatus status, long version) {}
}
