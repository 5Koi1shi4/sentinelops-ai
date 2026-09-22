package io.sentinelops.api.incident.evidence;

import static org.assertj.core.api.Assertions.*;

import io.sentinelops.api.support.PostgresIntegrationTest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class EvidenceMigrationIT extends PostgresIntegrationTest {
    @Autowired JdbcClient jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesInconsistentLegacyOwnershipWithoutRewritingHistory(boolean linkMismatch) {
        // Each case uses an isolated database inside this disposable test container.
        String database = "evidence_history_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("create database " + database).update();
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("11").load().migrate();
        var history = JdbcClient.create(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID service = history.sql("select id from service_catalog where service_key='checkout-api'").query(UUID.class).single();
        UUID principal = history.sql("select id from principal where subject='demo-author'").query(UUID.class).single();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (UUID incident : new UUID[] {first, second}) {
            history.sql("""
                    insert into incident(id,service_id,fingerprint,title,severity,status,opened_at,updated_at)
                    values (:id,:service,:fingerprint,'Legacy evidence','sev2','triaging',now(),now())
                    """).param("id", incident).param("service", service).param("fingerprint", incident.toString()).update();
        }
        UUID run = UUID.randomUUID();
        history.sql("""
                insert into diagnosis_run(id,incident_id,requested_by_principal_id,incident_version,
                  engine_type,status,prompt_version,input_hash,started_at)
                values (:id,:incident,:principal,0,'deterministic','running','test','test',now())
                """).param("id", run).param("incident", second).param("principal", principal).update();
        UUID evidence = UUID.randomUUID();
        history.sql("""
                insert into evidence_snapshot(id,incident_id,diagnosis_run_id,source_type,source_ref,
                  query_spec,redacted_payload,content_hash,captured_at)
                values (:id,:incident,:run,'loki','logs','{}','{}','historical-hash',now())
                """).param("id", evidence).param("incident", first)
                .param("run", linkMismatch ? null : run, java.sql.Types.OTHER).update();
        if (linkMismatch) {
            history.sql("insert into diagnosis_run_evidence values (:run,:evidence)")
                    .param("run", run).param("evidence", evidence).update();
        }
        assertThatThrownBy(() -> Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("12").load().migrate()).isInstanceOf(FlywayException.class);
        assertThat(history.sql("select incident_id from evidence_snapshot where id=:id").param("id", evidence)
                .query(UUID.class).single()).isEqualTo(first);
        assertThat(history.sql("select content_hash from evidence_snapshot where id=:id").param("id", evidence)
                .query(String.class).single()).isEqualTo("historical-hash");
        assertThat(history.sql("select count(*) from flyway_schema_history where version='12'").query(Long.class).single()).isZero();
    }
}
