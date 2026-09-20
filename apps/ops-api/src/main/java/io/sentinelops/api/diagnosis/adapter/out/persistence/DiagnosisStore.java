package io.sentinelops.api.diagnosis.adapter.out.persistence;

import io.sentinelops.api.diagnosis.domain.DiagnosisEvidence;
import io.sentinelops.api.incident.domain.IncidentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DiagnosisStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public DiagnosisStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<IncidentSnapshot> lockIncident(UUID incidentId) {
        return jdbc.sql("""
                        select id, service_id, status, version
                        from incident
                        where id = :incidentId
                        for update
                        """)
                .param("incidentId", incidentId)
                .query((resultSet, rowNumber) -> new IncidentSnapshot(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("service_id", UUID.class),
                        IncidentStatus.fromDatabase(resultSet.getString("status")),
                        resultSet.getLong("version")))
                .optional();
    }

    public AllocatedTransition transition(
            IncidentSnapshot incident, IncidentStatus target, Instant occurredAt) {
        return jdbc.sql("""
                        update incident
                        set status = :target,
                            version = version + 1,
                            next_event_seq = next_event_seq + 1,
                            updated_at = :occurredAt
                        where id = :incidentId
                          and status = :current
                          and version = :expectedVersion
                        returning version, next_event_seq - 1 as allocated_seq
                        """)
                .param("target", target.databaseValue())
                .param("occurredAt", databaseTimestamp(occurredAt))
                .param("incidentId", incident.id())
                .param("current", incident.status().databaseValue())
                .param("expectedVersion", incident.version())
                .query((resultSet, rowNumber) -> new AllocatedTransition(
                        new IncidentSnapshot(
                                incident.id(),
                                incident.serviceId(),
                                target,
                                resultSet.getLong("version")),
                        resultSet.getLong("allocated_seq")))
                .optional()
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "Incident changed while applying diagnosis transition"));
    }

    public long allocateEvent(UUID incidentId, Instant occurredAt) {
        return jdbc.sql("""
                        update incident
                        set next_event_seq = next_event_seq + 1, updated_at = :occurredAt
                        where id = :incidentId
                        returning next_event_seq - 1
                        """)
                .param("occurredAt", databaseTimestamp(occurredAt))
                .param("incidentId", incidentId)
                .query(Long.class)
                .single();
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
                          id, incident_id, seq_no, event_type, actor_type, actor_id,
                          payload, occurred_at
                        ) values (
                          :id, :incidentId, :sequence, :eventType, 'user', :actorId,
                          cast(:payload as jsonb), :occurredAt
                        )
                        """)
                .param("id", eventId)
                .param("incidentId", incidentId)
                .param("sequence", sequence)
                .param("eventType", eventType)
                .param("actorId", actorId)
                .param("payload", payloadJson)
                .param("occurredAt", databaseTimestamp(occurredAt))
                .update();
    }

    public List<DiagnosisEvidence> findEvidence(UUID incidentId) {
        return jdbc.sql("""
                        select id, source_type, source_ref, redacted_payload::text,
                               content_hash, captured_at, truncated
                        from evidence_snapshot
                        where incident_id = :incidentId
                        order by captured_at, id
                        """)
                .param("incidentId", incidentId)
                .query(this::mapEvidence)
                .list();
    }

    public Optional<UUID> findPrincipalId(String principalKey) {
        return jdbc.sql("""
                        select id from principal
                        where subject = :principalKey
                        order by created_at
                        limit 1
                        """)
                .param("principalKey", principalKey)
                .query(UUID.class)
                .optional();
    }

    public void insertRun(
            UUID runId,
            UUID incidentId,
            UUID principalId,
            long incidentVersion,
            String inputHash,
            Instant startedAt) {
        jdbc.sql("""
                        insert into diagnosis_run(
                          id, incident_id, requested_by_principal_id, incident_version,
                          engine_type, status, prompt_version, input_hash, started_at
                        ) values (
                          :id, :incidentId, :principalId, :incidentVersion,
                          'deterministic', 'running', 'deterministic-v1', :inputHash, :startedAt
                        )
                        """)
                .param("id", runId)
                .param("incidentId", incidentId)
                .param("principalId", principalId)
                .param("incidentVersion", incidentVersion)
                .param("inputHash", inputHash)
                .param("startedAt", databaseTimestamp(startedAt))
                .update();
    }

    public void completeRun(UUID runId, String status, String failureCode, Instant completedAt) {
        jdbc.sql("""
                        update diagnosis_run
                        set status = :status, failure_code = :failureCode, completed_at = :completedAt
                        where id = :runId and status = 'running'
                        """)
                .param("status", status)
                .param("failureCode", failureCode)
                .param("completedAt", databaseTimestamp(completedAt))
                .param("runId", runId)
                .update();
    }

    public void linkRunEvidence(UUID runId, UUID evidenceId) {
        jdbc.sql("""
                        insert into diagnosis_run_evidence(
                          diagnosis_run_id, evidence_snapshot_id
                        ) values (:runId, :evidenceId)
                        """)
                .param("runId", runId)
                .param("evidenceId", evidenceId)
                .update();
    }

    public void insertProposal(
            UUID proposalId,
            UUID runId,
            UUID incidentId,
            UUID runbookVersionId,
            String summary,
            String proposalPayload,
            String proposalHash,
            String riskLevel,
            Instant createdAt) {
        jdbc.sql("""
                        insert into diagnosis_proposal(
                          id, diagnosis_run_id, incident_id, runbook_version_id, summary,
                          proposal_payload, proposal_hash, risk_level, created_at
                        ) values (
                          :id, :runId, :incidentId, :runbookVersionId, :summary,
                          cast(:payload as jsonb), :proposalHash, :riskLevel, :createdAt
                        )
                        """)
                .param("id", proposalId)
                .param("runId", runId)
                .param("incidentId", incidentId)
                .param("runbookVersionId", runbookVersionId)
                .param("summary", summary)
                .param("payload", proposalPayload)
                .param("proposalHash", proposalHash)
                .param("riskLevel", riskLevel)
                .param("createdAt", databaseTimestamp(createdAt))
                .update();
    }

    public void linkEvidence(UUID proposalId, UUID evidenceId) {
        jdbc.sql("""
                        insert into diagnosis_proposal_evidence(proposal_id, evidence_snapshot_id)
                        values (:proposalId, :evidenceId)
                        """)
                .param("proposalId", proposalId)
                .param("evidenceId", evidenceId)
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

    private DiagnosisEvidence mapEvidence(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DiagnosisEvidence(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source_type"),
                resultSet.getString("source_ref"),
                objectMapper.readTree(resultSet.getString("redacted_payload")),
                resultSet.getString("content_hash"),
                resultSet.getObject("captured_at", OffsetDateTime.class).toInstant(),
                resultSet.getBoolean("truncated"));
    }

    private OffsetDateTime databaseTimestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record IncidentSnapshot(
            UUID id, UUID serviceId, IncidentStatus status, long version) {}

    public record AllocatedTransition(IncidentSnapshot incident, long sequence) {}
}
