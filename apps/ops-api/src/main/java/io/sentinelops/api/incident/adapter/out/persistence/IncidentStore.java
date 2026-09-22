package io.sentinelops.api.incident.adapter.out.persistence;

import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentSummary;
import io.sentinelops.api.incident.application.IncidentTimelineItem;
import io.sentinelops.api.incident.domain.IncidentStatus;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class IncidentStore {

    private final JdbcClient jdbc;

    public IncidentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockDelivery(String source, String sourceEventId) {
        jdbc.sql("""
                        select pg_advisory_xact_lock(
                          hashtextextended(:lockKey, 0)
                        )
                        """)
                .param("lockKey", source + '\u001f' + sourceEventId)
                .query((resultSet, rowNumber) -> 1)
                .single();
    }

    public Optional<UUID> findIncidentIdForDelivery(String source, String sourceEventId) {
        return jdbc.sql("""
                        select incident_id
                        from incident_event
                        where source = :source and source_event_id = :sourceEventId
                        """)
                .param("source", source)
                .param("sourceEventId", sourceEventId)
                .query(UUID.class)
                .optional();
    }

    public Optional<UUID> findServiceId(String serviceKey) {
        return jdbc.sql("select id from service_catalog where service_key = :serviceKey")
                .param("serviceKey", serviceKey)
                .query(UUID.class)
                .optional();
    }

    public AllocatedIncident upsertIncident(
            UUID candidateId, UUID serviceId, AlertEnvelope alert, Instant now) {
        return jdbc.sql("""
                        insert into incident(
                          id, service_id, fingerprint, title, severity, status,
                          version, next_event_seq, occurrence_count, opened_at, updated_at
                        ) values (
                          :id, :serviceId, :fingerprint, :title, :severity, 'detected',
                          0, 2, 1, :now, :now
                        )
                        on conflict (service_id, fingerprint)
                          where status not in ('resolved', 'suppressed')
                        do update set
                          occurrence_count = incident.occurrence_count + 1,
                          updated_at = excluded.updated_at,
                          next_event_seq = incident.next_event_seq + 1,
                          version = incident.version + 1
                        returning id, status, version, next_event_seq - 1 as allocated_seq
                        """)
                .param("id", candidateId)
                .param("serviceId", serviceId)
                .param("fingerprint", alert.fingerprint())
                .param("title", alert.title())
                .param("severity", alert.severity())
                .param("now", databaseTimestamp(now))
                .query((resultSet, rowNumber) -> new AllocatedIncident(
                        resultSet.getObject("id", UUID.class),
                        IncidentStatus.fromDatabase(resultSet.getString("status")),
                        resultSet.getLong("version"),
                        resultSet.getLong("allocated_seq")))
                .single();
    }

    public int appendSourceEvent(
            UUID eventId,
            AllocatedIncident incident,
            String eventType,
            AlertEnvelope alert,
            String sourceEventId,
            String payloadJson,
            Instant occurredAt) {
        return jdbc.sql("""
                        insert into incident_event(
                          id, incident_id, seq_no, event_type, actor_type, actor_id,
                          source, source_event_id, payload, occurred_at
                        ) values (
                          :id, :incidentId, :sequence, :eventType, 'integration', :actorId,
                          :source, :sourceEventId, cast(:payload as jsonb), :occurredAt
                        )
                        on conflict (source, source_event_id)
                          where source_event_id is not null
                        do nothing
                        """)
                .param("id", eventId)
                .param("incidentId", incident.id())
                .param("sequence", incident.allocatedSequence())
                .param("eventType", eventType)
                .param("actorId", alert.source())
                .param("source", alert.source())
                .param("sourceEventId", sourceEventId)
                .param("payload", payloadJson)
                .param("occurredAt", databaseTimestamp(occurredAt))
                .update();
    }

    public AllocatedIncident applyTransition(
            AllocatedIncident incident,
            IncidentStatus target,
            Instant transitionedAt) {
        int updated = jdbc.sql("""
                        update incident
                        set status = :target, updated_at = :transitionedAt
                        where id = :id and status = :current and version = :expectedVersion
                        """)
                .param("target", target.databaseValue())
                .param("transitionedAt", databaseTimestamp(transitionedAt))
                .param("id", incident.id())
                .param("current", incident.status().databaseValue())
                .param("expectedVersion", incident.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                    "Incident changed while applying a recovery transition");
        }
        return new AllocatedIncident(
                incident.id(), target, incident.version(), incident.allocatedSequence());
    }

    public int settleExecutionsForRecovery(UUID incidentId, Instant recoveredAt) {
        return jdbc.sql("""
                        update execution
                        set status = case
                              when status = 'pending' then 'verifying'
                              else 'unknown'
                            end,
                            claimed_by = null,
                            lease_until = null,
                            completed_at = :recoveredAt,
                            updated_at = :recoveredAt
                        where incident_id = :incidentId
                          and status in ('pending','running')
                        """)
                .param("incidentId", incidentId)
                .param("recoveredAt", databaseTimestamp(recoveredAt))
                .update();
    }

    public void appendOutboxEvent(
            UUID eventId,
            UUID incidentId,
            String eventType,
            String payloadJson,
            Instant createdAt) {
        jdbc.sql("""
                        insert into outbox_event(
                          id, aggregate_type, aggregate_id, event_type, payload, created_at
                        ) values (
                          :id, 'incident', :incidentId, :eventType,
                          cast(:payload as jsonb), :createdAt
                        )
                        """)
                .param("id", eventId)
                .param("incidentId", incidentId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .param("createdAt", databaseTimestamp(createdAt))
                .update();
    }

    public Optional<IncidentSummary> findSummary(UUID incidentId) {
        return jdbc.sql("""
                        select p.incident_id, p.service_id, s.service_key, p.title, p.severity,
                               p.status, p.resource_version, p.occurrence_count,
                               p.opened_at, p.updated_at, p.resolved_at
                        from incident_projection p
                        join service_catalog s on s.id = p.service_id
                        where p.incident_id = :id
                        """)
                .param("id", incidentId)
                .query(this::mapSummary)
                .optional();
    }

    public List<IncidentSummary> list(
            IncidentStatus status,
            Set<UUID> serviceIds,
            String severity,
            Instant cursorTime,
            UUID cursorId,
            int limit) {
        var sql = new StringBuilder("""
                select p.incident_id, p.service_id, s.service_key, p.title, p.severity,
                       p.status, p.resource_version, p.occurrence_count,
                       p.opened_at, p.updated_at, p.resolved_at
                from incident_projection p
                join service_catalog s on s.id = p.service_id
                where 1 = 1
                """);
        Map<String, Object> parameters = new HashMap<>();
        if (status != null) {
            sql.append(" and p.status = :status");
            parameters.put("status", status.databaseValue());
        }
        if (serviceIds != null) {
            sql.append(" and p.service_id in (:serviceIds)");
            parameters.put("serviceIds", serviceIds);
        }
        if (severity != null) {
            sql.append(" and p.severity = :severity");
            parameters.put("severity", severity);
        }
        if (cursorTime != null) {
            sql.append(" and (p.opened_at, p.incident_id) < (:cursorTime, :cursorId)");
            parameters.put("cursorTime", databaseTimestamp(cursorTime));
            parameters.put("cursorId", cursorId);
        }
        sql.append(" order by p.opened_at desc, p.incident_id desc limit :limit");
        parameters.put("limit", limit);

        return jdbc.sql(sql.toString()).params(parameters).query(this::mapSummary).list();
    }

    public List<IncidentTimelineItem> timeline(UUID incidentId, long afterSequence, int limit) {
        return jdbc.sql("""
                        select ie.id, ie.seq_no, ie.event_type, ie.actor_type, ie.actor_id,
                               ie.source, ie.source_event_id,
                               case
                                 when jsonb_typeof(ie.payload -> 'traceId') = 'string'
                                   and length(ie.payload ->> 'traceId') between 1 and 128
                                   and (ie.payload ->> 'traceId') ~ '^[A-Za-z0-9._:-]+$'
                                 then ie.payload ->> 'traceId'
                                 else null
                               end as trace_id,
                               array(
                                 select pe.evidence_snapshot_id
                                 from diagnosis_proposal_evidence pe
                                 join diagnosis_proposal proposal
                                   on proposal.id = pe.proposal_id
                                  and proposal.incident_id = ie.incident_id
                                 where pe.proposal_id::text = ie.payload ->> 'proposalId'
                                 order by pe.evidence_snapshot_id
                                 limit 100
                               ) as evidence_ids,
                               ie.occurred_at
                        from incident_event ie
                        where ie.incident_id = :incidentId and ie.seq_no > :afterSequence
                        order by ie.seq_no, ie.id
                        limit :limit
                        """)
                .param("incidentId", incidentId)
                .param("afterSequence", afterSequence)
                .param("limit", limit)
                .query(this::mapTimelineItem)
                .list();
    }

    private IncidentSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IncidentSummary(
                resultSet.getObject("incident_id", UUID.class),
                resultSet.getObject("service_id", UUID.class),
                resultSet.getString("service_key"),
                resultSet.getString("title"),
                resultSet.getString("severity"),
                IncidentStatus.fromDatabase(resultSet.getString("status")),
                resultSet.getLong("resource_version"),
                resultSet.getLong("occurrence_count"),
                instant(resultSet, "opened_at"),
                instant(resultSet, "updated_at"),
                nullableInstant(resultSet, "resolved_at"));
    }

    private IncidentTimelineItem mapTimelineItem(ResultSet resultSet, int rowNumber)
            throws SQLException {
        String eventType = resultSet.getString("event_type");
        return new IncidentTimelineItem(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("seq_no"),
                eventType,
                resultSet.getString("actor_type"),
                resultSet.getString("actor_id"),
                resultSet.getString("source"),
                resultSet.getString("source_event_id"),
                timelineSummary(eventType),
                resultSet.getString("trace_id"),
                evidenceIds(resultSet),
                instant(resultSet, "occurred_at"));
    }

    private List<UUID> evidenceIds(ResultSet resultSet) throws SQLException {
        Array values = resultSet.getArray("evidence_ids");
        try {
            return List.of((UUID[]) values.getArray());
        } finally {
            values.free();
        }
    }

    private String timelineSummary(String eventType) {
        return switch (eventType) {
            case "alert_received" -> "Alert received";
            case "alert_recovered" -> "Alert recovery received";
            case "diagnosis_started" -> "Diagnosis started";
            case "diagnosis_succeeded" -> "Diagnosis completed";
            case "diagnosis_validation_failed" -> "Diagnosis validation failed";
            case "approval_requested" -> "Approval requested";
            case "approval_granted" -> "Approval granted";
            case "approval_rejected" -> "Approval rejected";
            case "approval_invalidated" -> "Approval invalidated";
            case "execution_requested" -> "Execution requested";
            case "execution_completed" -> "Execution completed";
            case "execution_failed" -> "Execution failed";
            case "execution_authorization_invalidated" -> "Execution authorization invalidated";
            case "verification_succeeded" -> "Recovery verification succeeded";
            case "verification_failed" -> "Recovery verification failed";
            case "manual_verification_requested" -> "Manual verification requested";
            default -> "Incident event recorded";
        };
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime databaseTimestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record AllocatedIncident(
            UUID id, IncidentStatus status, long version, long allocatedSequence) {}
}
