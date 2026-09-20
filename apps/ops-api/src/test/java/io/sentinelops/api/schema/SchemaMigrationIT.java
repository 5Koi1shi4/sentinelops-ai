package io.sentinelops.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.sentinelops.api.support.PostgresIntegrationTest;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

class SchemaMigrationIT extends PostgresIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void createsCoreTablesAndIndexesAtVersionFour() {
        var tables = jdbc.sql("""
                        select table_name from information_schema.tables
                        where table_schema = 'public'
                        """)
                .query(String.class)
                .list();

        assertThat(tables).contains(
                "incident",
                "incident_event",
                "diagnosis_proposal",
                "incident_projection",
                "approval_request",
                "execution",
                "outbox_event",
                "audit_record");

        var indexes = jdbc.sql("""
                        select indexname from pg_indexes where schemaname = 'public'
                        """)
                .query(String.class)
                .list();

        assertThat(indexes).contains(
                "incident_active_fingerprint_uk",
                "incident_event_incident_seq_uk",
                "outbox_pending_claim_idx");

        var currentVersion = jdbc.sql("""
                        select version
                        from flyway_schema_history
                        where success
                        order by installed_rank desc
                        limit 1
                        """)
                .query(String.class)
                .single();

        assertThat(currentVersion).isEqualTo("4");
    }

    @Test
    void enforcesActiveFingerprintUniquenessAndAllowsReopenAfterResolution() {
        var serviceId = insertService("checkout-api");
        var firstIncidentId = insertIncident(serviceId, "payment-latency", "detected", 0, 1);

        var duplicateFailure = catchThrowable(
                () -> insertIncident(serviceId, "payment-latency", "triaging", 0, 1));

        assertThat(duplicateFailure).isInstanceOf(DataAccessException.class);
        assertThat(findSqlState(duplicateFailure)).isEqualTo("23505");

        jdbc.sql("""
                        update incident
                        set status = 'resolved', resolved_at = :resolvedAt,
                            updated_at = :resolvedAt, version = version + 1
                        where id = :id
                        """)
                .param("resolvedAt", OffsetDateTime.parse("2026-09-20T03:00:00Z"))
                .param("id", firstIncidentId)
                .update();

        var reopenedIncidentId =
                insertIncident(serviceId, "payment-latency", "detected", 0, 1);

        assertThat(reopenedIncidentId).isNotEqualTo(firstIncidentId);
        assertThat(jdbc.sql("""
                                select count(*) from incident
                                where service_id = :serviceId and fingerprint = :fingerprint
                                """)
                        .param("serviceId", serviceId)
                        .param("fingerprint", "payment-latency")
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    @Test
    void mirrorsAndRebuildsIncidentProjection() {
        var serviceId = insertService("inventory-api");
        var incidentId = insertIncident(serviceId, "stock-drift", "detected", 0, 1);
        var updatedAt = OffsetDateTime.parse("2026-09-20T04:30:00Z");

        jdbc.sql("""
                        update incident
                        set status = 'triaging', version = 7, occurrence_count = 3,
                            updated_at = :updatedAt
                        where id = :id
                        """)
                .param("updatedAt", updatedAt)
                .param("id", incidentId)
                .update();

        var projection = loadProjection(incidentId);
        assertThat(projection.status()).isEqualTo("triaging");
        assertThat(projection.resourceVersion()).isEqualTo(7L);
        assertThat(projection.occurrenceCount()).isEqualTo(3L);
        assertThat(projection.updatedAt()).isEqualTo(updatedAt);

        var beforeRebuild = projectionJson(incidentId);

        jdbc.sql("delete from incident_projection where incident_id = :id")
                .param("id", incidentId)
                .update();
        jdbc.sql("""
                        insert into incident_projection(
                          incident_id, service_id, title, severity, status, resource_version,
                          occurrence_count, opened_at, updated_at, resolved_at
                        )
                        select id, service_id, title, severity, status, version,
                               occurrence_count, opened_at, updated_at, resolved_at
                        from incident
                        where id = :id
                        """)
                .param("id", incidentId)
                .update();

        assertThat(projectionJson(incidentId)).isEqualTo(beforeRebuild);
    }

    @Test
    void rejectsEvidenceSnapshotMutationWithObjectNotInPrerequisiteState() {
        var serviceId = insertService("catalog-api");
        var incidentId = insertIncident(serviceId, "catalog-errors", "detected", 0, 1);
        var evidenceId = UUID.randomUUID();

        jdbc.sql("""
                        insert into evidence_snapshot(
                          id, incident_id, source_type, source_ref, query_spec,
                          redacted_payload, content_hash, captured_at
                        ) values (
                          :id, :incidentId, 'logs', 'loki://catalog', '{}'::jsonb,
                          '{}'::jsonb, 'sha256:evidence', :capturedAt
                        )
                        """)
                .param("id", evidenceId)
                .param("incidentId", incidentId)
                .param("capturedAt", OffsetDateTime.parse("2026-09-20T05:00:00Z"))
                .update();

        var mutationFailure = catchThrowable(() -> jdbc.sql("""
                        update evidence_snapshot
                        set source_ref = 'loki://tampered'
                        where id = :id
                        """)
                .param("id", evidenceId)
                .update());

        assertThat(mutationFailure).isInstanceOf(DataAccessException.class);
        assertThat(findSqlState(mutationFailure)).isEqualTo("55000");
    }

    private UUID insertService(String serviceKey) {
        var serviceId = UUID.randomUUID();
        var now = OffsetDateTime.parse("2026-09-20T02:00:00Z");

        jdbc.sql("""
                        insert into service_catalog(
                          id, service_key, display_name, owner_team, created_at, updated_at
                        ) values (:id, :serviceKey, :displayName, 'platform-sre', :now, :now)
                        """)
                .param("id", serviceId)
                .param("serviceKey", serviceKey)
                .param("displayName", serviceKey)
                .param("now", now)
                .update();

        return serviceId;
    }

    private UUID insertIncident(
            UUID serviceId, String fingerprint, String status, long version, long occurrenceCount) {
        var incidentId = UUID.randomUUID();
        var now = OffsetDateTime.parse("2026-09-20T02:30:00Z");

        jdbc.sql("""
                        insert into incident(
                          id, service_id, fingerprint, title, severity, status,
                          version, occurrence_count, opened_at, updated_at
                        ) values (
                          :id, :serviceId, :fingerprint, :title, 'sev2', :status,
                          :version, :occurrenceCount, :now, :now
                        )
                        """)
                .param("id", incidentId)
                .param("serviceId", serviceId)
                .param("fingerprint", fingerprint)
                .param("title", "Incident " + fingerprint)
                .param("status", status)
                .param("version", version)
                .param("occurrenceCount", occurrenceCount)
                .param("now", now)
                .update();

        return incidentId;
    }

    private ProjectionRow loadProjection(UUID incidentId) {
        return jdbc.sql("""
                        select status, resource_version, occurrence_count, updated_at
                        from incident_projection
                        where incident_id = :id
                        """)
                .param("id", incidentId)
                .query((resultSet, rowNumber) -> new ProjectionRow(
                        resultSet.getString("status"),
                        resultSet.getLong("resource_version"),
                        resultSet.getLong("occurrence_count"),
                        resultSet.getObject("updated_at", OffsetDateTime.class)))
                .single();
    }

    private String projectionJson(UUID incidentId) {
        return jdbc.sql("""
                        select row_to_json(projection)::text
                        from incident_projection projection
                        where incident_id = :id
                        """)
                .param("id", incidentId)
                .query(String.class)
                .single();
    }

    private String findSqlState(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    private record ProjectionRow(
            String status, long resourceVersion, long occurrenceCount, OffsetDateTime updatedAt) {}
}
