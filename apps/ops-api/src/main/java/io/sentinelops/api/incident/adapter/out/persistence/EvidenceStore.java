package io.sentinelops.api.incident.adapter.out.persistence;

import io.sentinelops.api.incident.application.evidence.EvidenceSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class EvidenceStore {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public EvidenceStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Locks in incident → run order only during the final short freeze transaction. */
    public void requireRunningScope(UUID incidentId, UUID runId, UUID serviceId, boolean lock) {
        long version = jdbc.sql("select version from incident where id=:incident and service_id=:service"
                        + (lock ? " for update" : ""))
                .param("incident", incidentId).param("service", serviceId).query(Long.class).optional()
                .orElseThrow(EvidenceStore::invalidScope);
        long runVersion = jdbc.sql("select incident_version from diagnosis_run where id=:run and incident_id=:incident and status='running' and lease_expires_at>clock_timestamp()"
                        + (lock ? " for update" : ""))
                .param("run", runId).param("incident", incidentId).query(Long.class).optional()
                .orElseThrow(EvidenceStore::invalidScope);
        if (version != runVersion) throw invalidScope();
    }

    public EvidenceSnapshot freeze(UUID incidentId, UUID runId, EvidenceSnapshot candidate) {
        var payload = candidate.payload();
        var querySpec = mapper.createObjectNode();
        for (String field : new String[] {"sourceType", "queryId", "from", "to", "parameters"}) {
            querySpec.set(field, payload.path(field));
        }
        jdbc.sql("""
                insert into evidence_snapshot(id,incident_id,diagnosis_run_id,source_type,source_ref,
                  query_spec,redacted_payload,content_hash,captured_at,truncated)
                values (:id,:incident,:run,:source,:query,cast(:spec as jsonb),cast(:payload as jsonb),:hash,:at,:truncated)
                on conflict (incident_id,content_hash) do nothing
                """).param("id", candidate.id()).param("incident", incidentId).param("run", runId)
                .param("source", candidate.sourceType()).param("query", candidate.queryId())
                .param("spec", mapper.writeValueAsString(querySpec)).param("payload", mapper.writeValueAsString(payload))
                .param("hash", candidate.contentHash()).param("at", OffsetDateTime.ofInstant(candidate.capturedAt(), ZoneOffset.UTC))
                .param("truncated", candidate.truncated()).update();
        var frozen = jdbc.sql("select * from evidence_snapshot where incident_id=:incident and content_hash=:hash")
                .param("incident", incidentId).param("hash", candidate.contentHash()).query(this::map).single();
        jdbc.sql("""
                insert into diagnosis_run_evidence(diagnosis_run_id,evidence_snapshot_id) values (:run,:evidence)
                on conflict (diagnosis_run_id,evidence_snapshot_id) do nothing
                """).param("run", runId).param("evidence", frozen.id()).update();
        return frozen;
    }

    public EvidenceSnapshot getLinked(UUID incidentId, UUID runId, UUID serviceId, UUID evidenceId) {
        return jdbc.sql("""
                select e.* from evidence_snapshot e
                join diagnosis_run_evidence l on l.evidence_snapshot_id=e.id
                join diagnosis_run r on r.id=l.diagnosis_run_id and r.incident_id=e.incident_id
                join incident i on i.id=e.incident_id
                where e.id=:evidence and i.id=:incident and i.service_id=:service and r.id=:run
                """).param("evidence", evidenceId).param("incident", incidentId)
                .param("service", serviceId).param("run", runId).query(this::map).optional()
                .orElseThrow(EvidenceStore::invalidScope);
    }

    private EvidenceSnapshot map(ResultSet row, int rowNumber) throws SQLException {
        var payload = mapper.readTree(row.getString("redacted_payload"));
        Set<String> rules = new TreeSet<>();
        for (var rule : payload.path("redaction").path("rules")) rules.add(rule.asString());
        return new EvidenceSnapshot(row.getObject("id", UUID.class), row.getString("source_type"),
                row.getString("source_ref"), row.getObject("captured_at", OffsetDateTime.class).toInstant(),
                row.getString("content_hash"), row.getBoolean("truncated"),
                payload.path("redaction").path("count").asInt(0), rules, payload);
    }

    private static IllegalArgumentException invalidScope() {
        return new IllegalArgumentException("evidence scope is unavailable or stale");
    }
}
