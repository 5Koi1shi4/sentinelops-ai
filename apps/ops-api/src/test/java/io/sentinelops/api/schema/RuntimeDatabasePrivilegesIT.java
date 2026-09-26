package io.sentinelops.api.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RuntimeDatabasePrivilegesIT {

    private static final String APP_USER = "sentinelops_app";
    private static final String APP_PASSWORD = "task6-app-test-password";
    private static final String MIGRATOR_USER = "sentinelops_migrator";
    private static final String MIGRATOR_PASSWORD = "task6-migrator-test-password";
    private static final String LEGACY_PROBE = "task6_existing_probe";
    private static final String LEGACY_ROUTINE = "task6_legacy_probe_routine";
    private static final String FUTURE_PROBE = "task6_future_probe";
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");
    private static final Map<String, String> APPEND_ONLY_TABLES = Map.ofEntries(
            Map.entry("audit_record", "id"),
            Map.entry("incident_event", "id"),
            Map.entry("execution_attempt", "id"),
            Map.entry("execution_attempt_event", "id"),
            Map.entry("evidence_snapshot", "id"),
            Map.entry("diagnosis_proposal", "id"),
            Map.entry("diagnosis_proposal_evidence", "proposal_id"),
            Map.entry("diagnosis_run_evidence", "diagnosis_run_id"),
            Map.entry("verification_attempt", "id"),
            Map.entry("approval_decision", "id"),
            Map.entry("knowledge_chunk", "id"),
            Map.entry("eval_dataset", "id"),
            Map.entry("eval_case", "id"),
            Map.entry("eval_case_result", "id"));

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("sentinelops")
            .withUsername("sentinelops")
            .withPassword("sentinelops");

    @BeforeAll
    static void prepareDatabaseWithSeparateMigrationAndRuntimeRoles() throws Exception {
        Path postgresScripts = projectRoot().resolve("deploy/postgres");
        Path rolesScript = postgresScripts.resolve("init/001_roles.sql");
        Path grantsScript = postgresScripts.resolve("grants/runtime-grants.sql");
        Path provisionerScript = postgresScripts.resolve("provision-roles.sh");
        assertTrue(Files.isRegularFile(rolesScript), "init/001_roles.sql must be available to this integration test");
        assertTrue(Files.isRegularFile(grantsScript), "grants/runtime-grants.sql must be available to this integration test");
        assertTrue(Files.isRegularFile(provisionerScript), "provision-roles.sh must be available to this integration test");

        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            try (ResultSet result = statement.executeQuery(
                    "select exists (select 1 from pg_available_extensions where name = 'hstore')")) {
                assertTrue(result.next() && result.getBoolean(1), "hstore must be available to test extension denial");
            }
        }

        POSTGRES.execInContainer(
                "mkdir", "-p", "/tmp/sentinelops-postgres/init", "/tmp/sentinelops-postgres/grants",
                "/tmp/sentinelops-postgres/secrets");
        POSTGRES.copyFileToContainer(
                Transferable.of(Files.readAllBytes(rolesScript), 0444),
                "/tmp/sentinelops-postgres/init/001_roles.sql");
        POSTGRES.copyFileToContainer(
                Transferable.of(Files.readAllBytes(grantsScript), 0444),
                "/tmp/sentinelops-postgres/grants/runtime-grants.sql");
        POSTGRES.copyFileToContainer(
                Transferable.of(Files.readAllBytes(provisionerScript), 0444),
                "/tmp/sentinelops-postgres/provision-roles.sh");
        applyRoleAndGrantScripts();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD)
                .locations("classpath:db/migration", "classpath:db/callback")
                .load()
                .migrate();

        try (Connection app = appConnection(); Statement statement = app.createStatement();
                ResultSet result = statement.executeQuery(
                        "select has_table_privilege(current_user, 'public.service_catalog', 'UPDATE')")) {
            assertTrue(result.next() && result.getBoolean(1),
                    "Flyway afterMigrate callback must grant reviewed UPDATE to mutable runtime tables");
        }
        try (Connection app = appConnection()) {
            assertPermissionDenied(app, "select version from flyway_schema_history");
            assertPermissionDenied(app, "insert into flyway_schema_history(installed_rank) values (987654)");
            verifyRuntimeDeleteExceptions(app);
        }

        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.execute("create table " + LEGACY_PROBE + " (id integer primary key, value text not null)");
            statement.execute("insert into " + LEGACY_PROBE + " (id, value) values (1, 'created before role setup')");
            statement.execute("create function " + LEGACY_ROUTINE + "() returns integer language sql as $$ select 1 $$");
            statement.execute("alter function " + LEGACY_ROUTINE + "() owner to sentinelops");
            statement.execute("alter table service_catalog owner to sentinelops");
        }
        applyRoleAndGrantScripts();

        try (Connection migrator = connection(MIGRATOR_USER, MIGRATOR_PASSWORD);
                Statement statement = migrator.createStatement()) {
            statement.execute("alter table " + LEGACY_PROBE + " add column migrator_verified boolean not null default true");
        }
        try (Connection migrator = connection(MIGRATOR_USER, MIGRATOR_PASSWORD);
                Statement statement = migrator.createStatement()) {
            statement.execute("create table " + FUTURE_PROBE + " (id integer primary key, value text not null)");
            statement.execute("create function " + FUTURE_PROBE + "_routine() returns integer language sql as $$ select 1 $$");
        }
    }

    @Test
    void runtimeRoleCanReadInsertAndUpdateMutableRowsAndTheirProjection() throws SQLException {
        UUID serviceId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        try (Connection app = appConnection()) {
            try (PreparedStatement insert = app.prepareStatement("""
                    insert into service_catalog(id, service_key, display_name, owner_team, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setObject(1, serviceId);
                insert.setString(2, "task6-" + serviceId);
                insert.setString(3, "Task 6 service");
                insert.setString(4, "platform");
                insert.setTimestamp(5, now);
                insert.setTimestamp(6, now);
                assertEquals(1, insert.executeUpdate());
            }
            try (PreparedStatement update = app.prepareStatement(
                    "update service_catalog set display_name = ? where id = ?")) {
                update.setString(1, "Updated Task 6 service");
                update.setObject(2, serviceId);
                assertEquals(1, update.executeUpdate());
            }
            try (PreparedStatement select = app.prepareStatement(
                    "select display_name from service_catalog where id = ?")) {
                select.setObject(1, serviceId);
                try (ResultSet result = select.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("Updated Task 6 service", result.getString(1));
                }
            }

            try (PreparedStatement insertIncident = app.prepareStatement("""
                    insert into incident(id, service_id, fingerprint, title, severity, status, opened_at, updated_at)
                    values (?, ?, ?, ?, 'sev2', 'detected', ?, ?)
                    """)) {
                insertIncident.setObject(1, incidentId);
                insertIncident.setObject(2, serviceId);
                insertIncident.setString(3, "task6-" + incidentId);
                insertIncident.setString(4, "Permission test incident");
                insertIncident.setTimestamp(5, now);
                insertIncident.setTimestamp(6, now);
                assertEquals(1, insertIncident.executeUpdate());
            }
            try (PreparedStatement projection = app.prepareStatement(
                    "select title from incident_projection where incident_id = ?")) {
                projection.setObject(1, incidentId);
                try (ResultSet result = projection.executeQuery()) {
                    assertTrue(result.next(), "the incident trigger must write its projection as the runtime role");
                    assertEquals("Permission test incident", result.getString(1));
                }
            }
        }
    }

    @Test
    void runtimeRoleCanAppendEvalCasesWhileDatasetRemainsImmutable() throws SQLException {
        UUID principalId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        try (Connection app = appConnection()) {
            try (Statement permissions = app.createStatement();
                    ResultSet result = permissions.executeQuery(
                            "select has_table_privilege(current_user, 'public.eval_dataset', 'UPDATE')")) {
                assertTrue(result.next());
                assertFalse(result.getBoolean(1), "runtime role must not update immutable Eval datasets");
            }
            try (PreparedStatement principal = app.prepareStatement("""
                    insert into principal(id, issuer, subject, display_name, created_at)
                    values (?, 'https://runtime-privileges.invalid', ?, 'Eval fixture', clock_timestamp())
                    """)) {
                principal.setObject(1, principalId);
                principal.setString(2, "eval-" + principalId);
                assertEquals(1, principal.executeUpdate());
            }
            try (PreparedStatement dataset = app.prepareStatement("""
                    insert into eval_dataset(id, dataset_key, version_number, checksum,
                                             created_by_principal_id, created_at)
                    values (?, ?, 1, ?, ?, clock_timestamp())
                    """)) {
                dataset.setObject(1, datasetId);
                dataset.setString(2, "runtime-" + datasetId);
                dataset.setString(3, "checksum-" + datasetId);
                dataset.setObject(4, principalId);
                assertEquals(1, dataset.executeUpdate());
            }
            try (PreparedStatement item = app.prepareStatement("""
                    insert into eval_case(id, dataset_id, case_key, input_fixture,
                                          expectation, tags, checksum)
                    values (?, ?, 'runtime-case', '{}'::jsonb, '{}'::jsonb,
                            array['runtime'], ?)
                    """)) {
                item.setObject(1, caseId);
                item.setObject(2, datasetId);
                item.setString(3, "checksum-" + caseId);
                assertEquals(1, item.executeUpdate());
            }
        }
    }

    @Test
    void runtimeRoleHasNoDatabaseDdlRoleOrExtensionPrivileges() throws SQLException {
        try (Connection app = appConnection(); Statement statement = app.createStatement()) {
            try (ResultSet result = statement.executeQuery("select current_user")) {
                assertTrue(result.next());
                assertEquals(APP_USER, result.getString(1));
            }
            try (ResultSet result = statement.executeQuery("""
                    select rolsuper, rolcreatedb, rolcreaterole
                    from pg_roles where rolname = current_user
                    """)) {
                assertTrue(result.next());
                assertFalse(result.getBoolean(1), "runtime role must not be superuser");
                assertFalse(result.getBoolean(2), "runtime role must not create databases");
                assertFalse(result.getBoolean(3), "runtime role must not create roles");
            }

            assertPermissionDenied(app, "create schema task6_runtime_schema_probe");
            assertPermissionDenied(app, "create table public.task6_runtime_ddl_probe (id integer)");
            assertPermissionDenied(app, "alter table public.service_catalog add column task6_forbidden text");
            assertPermissionDenied(app, "create role task6_runtime_role_probe");
            assertPermissionDenied(app, "create extension hstore");
            assertPermissionDenied(app, "select version from flyway_schema_history");
        }
    }

    @Test
    void runtimeRoleCanOnlyDeleteWebhookNoncesAndRevokedRoleGrants() throws SQLException {
        UUID principalId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        try (Connection app = appConnection()) {
            try (PreparedStatement insertPrincipal = app.prepareStatement("""
                    insert into principal(id, issuer, subject, display_name, created_at)
                    values (?, 'https://task6.invalid', ?, 'Task 6 principal', ?)
                    """)) {
                insertPrincipal.setObject(1, principalId);
                insertPrincipal.setString(2, principalId.toString());
                insertPrincipal.setTimestamp(3, now);
                assertEquals(1, insertPrincipal.executeUpdate());
            }
            try (PreparedStatement insertGrant = app.prepareStatement("""
                    insert into role_grant(id, principal_id, role_name, granted_at)
                    values (?, ?, 'observer', ?)
                    """)) {
                insertGrant.setObject(1, grantId);
                insertGrant.setObject(2, principalId);
                insertGrant.setTimestamp(3, now);
                assertEquals(1, insertGrant.executeUpdate());
            }
            try (PreparedStatement deleteGrant = app.prepareStatement("delete from role_grant where id = ?")) {
                deleteGrant.setObject(1, grantId);
                assertEquals(1, deleteGrant.executeUpdate());
            }

            String nonceHash = "a".repeat(64);
            try (PreparedStatement insertNonce = app.prepareStatement("""
                    insert into webhook_replay_nonce(source_key, nonce_hash, observed_at, expires_at)
                    values ('task6-test', ?, ?, ?)
                    """)) {
                insertNonce.setString(1, nonceHash);
                insertNonce.setTimestamp(2, now);
                insertNonce.setTimestamp(3, Timestamp.from(now.toInstant().plusSeconds(60)));
                assertEquals(1, insertNonce.executeUpdate());
            }
            try (PreparedStatement duplicateNonce = app.prepareStatement("""
                    insert into webhook_replay_nonce(source_key, nonce_hash, observed_at, expires_at)
                    values ('task6-test', ?, ?, ?)
                    on conflict do nothing
                    """)) {
                duplicateNonce.setString(1, nonceHash);
                duplicateNonce.setTimestamp(2, now);
                duplicateNonce.setTimestamp(3, Timestamp.from(now.toInstant().plusSeconds(60)));
                assertEquals(0, duplicateNonce.executeUpdate(),
                        "the webhook replay guard must keep duplicate claims idempotent under RLS");
            }
            try (PreparedStatement deleteNonce = app.prepareStatement(
                    "delete from webhook_replay_nonce where source_key = 'task6-test' and nonce_hash = ?")) {
                deleteNonce.setString(1, nonceHash);
                assertEquals(0, deleteNonce.executeUpdate(),
                        "a runtime credential must not delete a nonce that is still valid");
            }
            try (PreparedStatement findValidNonce = app.prepareStatement("""
                    select exists (
                      select 1 from webhook_replay_nonce
                      where source_key = 'task6-test' and nonce_hash = ?)
                    """)) {
                findValidNonce.setString(1, nonceHash);
                try (ResultSet result = findValidNonce.executeQuery()) {
                    assertTrue(result.next() && result.getBoolean(1),
                            "the valid nonce must remain stored after the runtime delete attempt");
                }
            }

            String expiredSource = "task6-expired-" + UUID.randomUUID();
            String expiredNonceHash = "d".repeat(64);
            try (PreparedStatement insertExpiredNonce = app.prepareStatement("""
                    insert into webhook_replay_nonce(source_key, nonce_hash, observed_at, expires_at)
                    values (?, ?, ?, ?)
                    """)) {
                insertExpiredNonce.setString(1, expiredSource);
                insertExpiredNonce.setString(2, expiredNonceHash);
                insertExpiredNonce.setTimestamp(3, Timestamp.from(now.toInstant().minusSeconds(120)));
                insertExpiredNonce.setTimestamp(4, Timestamp.from(now.toInstant().minusSeconds(60)));
                assertEquals(1, insertExpiredNonce.executeUpdate());
            }
            try (PreparedStatement cleanupExpiredNonces = app.prepareStatement("""
                    with expired as (
                      select source_key, nonce_hash
                      from webhook_replay_nonce
                      where expires_at < ?
                      order by expires_at
                      limit 1000
                    )
                    delete from webhook_replay_nonce as nonce
                    using expired
                    where nonce.source_key = expired.source_key
                      and nonce.nonce_hash = expired.nonce_hash
                    """)) {
                cleanupExpiredNonces.setTimestamp(1, Timestamp.from(Instant.now()));
                assertEquals(1, cleanupExpiredNonces.executeUpdate(),
                        "the existing removeExpired CTE must clean expired nonce rows under RLS");
            }

            assertPermissionDenied(app, "delete from service_catalog");
            assertPermissionDenied(app, "delete from principal");
            assertPermissionDenied(app, "delete from execution");
            assertPermissionDenied(app, "alter table public.webhook_replay_nonce disable row level security");
        }
    }

    @Test
    void migrationOwnerCanStillManageWebhookNonces() throws SQLException {
        String source = "task6-migrator-owner-" + UUID.randomUUID();
        String nonceHash = "e".repeat(64);
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection app = appConnection(); PreparedStatement insertNonce = app.prepareStatement("""
                insert into webhook_replay_nonce(source_key, nonce_hash, observed_at, expires_at)
                values (?, ?, ?, ?)
                """)) {
            insertNonce.setString(1, source);
            insertNonce.setString(2, nonceHash);
            insertNonce.setTimestamp(3, now);
            insertNonce.setTimestamp(4, Timestamp.from(now.toInstant().plusSeconds(60)));
            assertEquals(1, insertNonce.executeUpdate());
        }

        try (Connection migrator = connection(MIGRATOR_USER, MIGRATOR_PASSWORD);
                PreparedStatement deleteNonce = migrator.prepareStatement(
                        "delete from webhook_replay_nonce where source_key = ? and nonce_hash = ?")) {
            try (ResultSet result = migrator.createStatement().executeQuery("select current_user")) {
                assertTrue(result.next());
                assertEquals(MIGRATOR_USER, result.getString(1));
            }
            deleteNonce.setString(1, source);
            deleteNonce.setString(2, nonceHash);
            assertEquals(1, deleteNonce.executeUpdate());
        }
    }

    @Test
    void existingAndFutureTablesReceiveReviewedDefaultPrivileges() throws SQLException {
        try (Connection app = appConnection(); Statement statement = app.createStatement()) {
            try (ResultSet result = statement.executeQuery("select value from " + LEGACY_PROBE + " where id = 1")) {
                assertTrue(result.next());
                assertEquals("created before role setup", result.getString(1));
            }
            assertEquals(1, statement.executeUpdate(
                    "insert into " + LEGACY_PROBE + " (id, value) values (2, 'runtime insert')"));
            assertPermissionDenied(app, "update " + LEGACY_PROBE + " set value = 'unreviewed' where id = 2");

            assertEquals(1, statement.executeUpdate(
                    "insert into " + FUTURE_PROBE + " (id, value) values (1, 'future runtime insert')"));
            try (ResultSet result = statement.executeQuery("select value from " + FUTURE_PROBE + " where id = 1")) {
                assertTrue(result.next());
                assertEquals("future runtime insert", result.getString(1));
            }
            assertPermissionDenied(app, "update " + FUTURE_PROBE + " set value = 'unreviewed' where id = 1");
            assertPermissionDenied(app, "delete from " + FUTURE_PROBE);
        }
    }

    @Test
    void appendOnlyTablesRejectRuntimeUpdateAndDelete() throws SQLException {
        try (Connection app = appConnection()) {
            for (Map.Entry<String, String> entry : APPEND_ONLY_TABLES.entrySet()) {
                String table = entry.getKey();
                String column = entry.getValue();
                assertPermissionDenied(app, "update " + table + " set " + column + " = " + column);
                assertPermissionDenied(app, "delete from " + table);
            }
        }
    }

    @Test
    void runtimeRoleCannotExecuteApplicationRoutinesButCanSearchVectors() throws SQLException {
        try (Connection app = appConnection(); Statement statement = app.createStatement()) {
            String routinePrivilegesSql = """
                    select has_function_privilege(current_user, 'public.reject_row_mutation()', 'EXECUTE'),
                           has_function_privilege(current_user, 'public.%s()', 'EXECUTE'),
                           has_function_privilege(current_user, 'public.task6_future_probe_routine()', 'EXECUTE')
                    """.formatted(LEGACY_ROUTINE);
            try (ResultSet result = statement.executeQuery(routinePrivilegesSql)) {
                assertTrue(result.next());
                assertFalse(result.getBoolean(1), "runtime role must not execute migration-owned trigger functions");
                assertFalse(result.getBoolean(2), "existing routines must not retain PUBLIC EXECUTE");
                assertFalse(result.getBoolean(3), "future migration routines must not default to PUBLIC EXECUTE");
            }
            try (ResultSet result = statement.executeQuery(
                    "select '[1,2,3]'::vector <=> '[1,2,3]'::vector")) {
                assertTrue(result.next());
                assertEquals(0.0, result.getDouble(1), 0.0001,
                        "runtime role must retain pgvector search operators");
            }
        }
    }

    @Test
    void flywayCanMigrateAnEmptyDatabaseBeforeRuntimeRolesExist() throws Exception {
        try (PostgreSQLContainer rolelessPostgres = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("sentinelops")
                .withUsername("sentinelops")
                .withPassword("sentinelops")) {
            rolelessPostgres.start();

            try (Connection connection = connection(
                    rolelessPostgres.getJdbcUrl(), rolelessPostgres.getUsername(), rolelessPostgres.getPassword());
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("""
                            select count(*) = 0 from pg_roles
                            where rolname in ('sentinelops_app', 'sentinelops_migrator')
                            """)) {
                assertTrue(result.next() && result.getBoolean(1),
                        "the disposable database must not pre-create Task 6 roles");
            }

            Flyway.configure()
                    .dataSource(
                            rolelessPostgres.getJdbcUrl(),
                            rolelessPostgres.getUsername(),
                            rolelessPostgres.getPassword())
                    .locations("classpath:db/migration", "classpath:db/callback")
                    .load()
                    .migrate();

            try (Connection connection = connection(
                    rolelessPostgres.getJdbcUrl(), rolelessPostgres.getUsername(), rolelessPostgres.getPassword());
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("""
                            select current_user,
                                   exists (select 1 from pg_roles where rolname = 'sentinelops_app'),
                                   exists (select 1 from pg_roles where rolname = 'sentinelops_migrator'),
                                   (select version from public.flyway_schema_history order by installed_rank desc limit 1),
                                   (select installed_by from public.flyway_schema_history order by installed_rank desc limit 1),
                                   (select relrowsecurity from pg_class where oid = 'public.webhook_replay_nonce'::regclass)
                            """)) {
                assertTrue(result.next());
                assertEquals(rolelessPostgres.getUsername(), result.getString(1));
                assertFalse(result.getBoolean(2), "migrations must not create the runtime role");
                assertFalse(result.getBoolean(3), "migrations must not create the migration role");
                assertEquals("20", result.getString(4));
                assertEquals(rolelessPostgres.getUsername(), result.getString(5));
                assertTrue(result.getBoolean(6), "the webhook nonce RLS policy must exist after migration");
            }

            try (Connection connection = connection(
                    rolelessPostgres.getJdbcUrl(), rolelessPostgres.getUsername(), rolelessPostgres.getPassword());
                    PreparedStatement statement = connection.prepareStatement("""
                            select count(*) from pg_policies
                            where schemaname = 'public'
                              and tablename = 'webhook_replay_nonce'
                              and policyname in (
                                'webhook_replay_nonce_app_select',
                                'webhook_replay_nonce_app_insert',
                                'webhook_replay_nonce_app_delete_expired')
                              and roles = array['public'::name]
                            """);
                    ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(3, result.getInt(1),
                        "all nonce policies must be role-order independent and apply to PUBLIC");
            }
        }
    }

    @Test
    void publicRlsPoliciesDoNotGrantTablePrivileges() throws SQLException {
        String probeRole = "task6_no_dml_probe";
        String probePassword = "task6-no-dml-test-password";
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.execute("create role " + probeRole + " login password '" + probePassword + "' nosuperuser nobypassrls");
            statement.execute("grant connect on database sentinelops to " + probeRole);
            statement.execute("grant usage on schema public to " + probeRole);
        }

        try {
            try (Connection probe = connection(probeRole, probePassword)) {
                assertPermissionDenied(probe, "select * from public.webhook_replay_nonce");
                assertPermissionDenied(probe, "insert into public.webhook_replay_nonce(source_key, nonce_hash, observed_at, expires_at) "
                        + "values ('task6-no-dml', repeat('c', 64), now(), now() + interval '1 minute')");
                assertPermissionDenied(probe, "delete from public.webhook_replay_nonce");
            }
        } finally {
            try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
                statement.execute("revoke connect on database sentinelops from " + probeRole);
                statement.execute("revoke usage on schema public from " + probeRole);
                statement.execute("drop role if exists " + probeRole);
            }
        }
    }

    @Test
    void executorConfigurationContainsNoDatabaseConnectionProperties() throws Exception {
        Path executorConfiguration = projectRoot().resolve("apps/ops-executor/src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(executorConfiguration), "Executor application configuration must exist");

        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("executor-application", new FileSystemResource(executorConfiguration));
        for (String key : List.of(
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "SENTINELOPS_DB_URL",
                "SENTINELOPS_DB_USERNAME",
                "SENTINELOPS_DB_PASSWORD")) {
            assertTrue(sources.stream().noneMatch(source -> source.containsProperty(key)),
                    "Executor configuration must not define " + key);
        }
    }

    @Test
    void provisionerRejectsPasswordFilesContainingCarriageReturnOrLineFeed() throws Exception {
        String adminPath = "/tmp/sentinelops-postgres/secrets/db-admin-password";
        String migratorPath = "/tmp/sentinelops-postgres/secrets/db-migrator-password";
        String appPath = "/tmp/sentinelops-postgres/secrets/db-app-password";
        Map<String, String> secretPaths = Map.of(
                "SENTINELOPS_DB_ADMIN_PASSWORD_FILE", adminPath,
                "SENTINELOPS_DB_MIGRATOR_PASSWORD_FILE", migratorPath,
                "SENTINELOPS_DB_APP_PASSWORD_FILE", appPath);
        Map<String, String> validSecrets = Map.of(
                adminPath, POSTGRES.getPassword(),
                migratorPath, MIGRATOR_PASSWORD,
                appPath, APP_PASSWORD);

        for (Map.Entry<String, String> entry : secretPaths.entrySet()) {
            for (Map.Entry<String, String> secret : validSecrets.entrySet()) {
                POSTGRES.copyFileToContainer(
                        Transferable.of(secret.getValue().getBytes(StandardCharsets.UTF_8), 0400), secret.getKey());
            }
            String secretPath = entry.getValue();
            String invalidSecret = validSecrets.get(secretPath) + "\r\n";
            POSTGRES.copyFileToContainer(
                    Transferable.of(invalidSecret.getBytes(StandardCharsets.UTF_8), 0400), secretPath);

            ExecConfig config = ExecConfig.builder()
                    .command(new String[] {"sh", "/tmp/sentinelops-postgres/provision-roles.sh"})
                    .envVars(Map.of(
                            "SENTINELOPS_DB_ADMIN_PASSWORD_FILE", adminPath,
                            "SENTINELOPS_DB_MIGRATOR_PASSWORD_FILE", migratorPath,
                            "SENTINELOPS_DB_APP_PASSWORD_FILE", appPath,
                            "SENTINELOPS_DB_HOST", "localhost",
                            "SENTINELOPS_DB_PORT", "5432",
                            "SENTINELOPS_DB_NAME", POSTGRES.getDatabaseName(),
                            "SENTINELOPS_DB_ADMIN_USER", POSTGRES.getUsername()))
                    .build();
            var result = POSTGRES.execInContainer(config);
            String output = result.getStdout() + result.getStderr();
            assertTrue(result.getExitCode() != 0,
                    entry.getKey() + " must reject a password file containing CR/LF without trimming it");
            assertTrue(output.contains("must not contain CR or LF"),
                    "the provisioner must explain the secret-file format requirement without printing its contents");
        }
    }

    @Test
    void apiUsesSeparateRuntimeAndFlywayCredentials() throws Exception {
        Path apiConfiguration = projectRoot().resolve("apps/ops-api/src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(apiConfiguration), "API application configuration must exist");

        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("api-application", new FileSystemResource(apiConfiguration));
        assertEquals(
                "${SENTINELOPS_DB_USERNAME:sentinelops_app}",
                property(sources, "spring.datasource.username"));
        assertEquals(
                "${SENTINELOPS_DB_MIGRATOR_USERNAME:${spring.datasource.username}}",
                property(sources, "spring.flyway.user"));
        assertEquals("${SENTINELOPS_DB_PASSWORD:}", property(sources, "spring.datasource.password"));
        assertEquals(
                "${SENTINELOPS_DB_MIGRATOR_PASSWORD:${spring.datasource.password}}",
                property(sources, "spring.flyway.password"));
        assertEquals("classpath:db/migration,classpath:db/callback", property(sources, "spring.flyway.locations"));

        Path coreCompose = projectRoot().resolve("deploy/compose/compose.core.yml");
        List<PropertySource<?>> composeSources = new YamlPropertySourceLoader()
                .load("core-compose", new FileSystemResource(coreCompose));
        assertEquals(
                "sentinelops_app",
                property(composeSources, "services.ops-api.environment.SENTINELOPS_DB_USERNAME"));
        assertEquals(
                "sentinelops_migrator",
                property(composeSources, "services.ops-api.environment.SENTINELOPS_DB_MIGRATOR_USERNAME"));
    }

    private static String property(List<PropertySource<?>> sources, String key) {
        return sources.stream()
                .filter(source -> source.containsProperty(key))
                .map(source -> String.valueOf(source.getProperty(key)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing application property " + key));
    }

    private static void applyRoleAndGrantScripts() throws Exception {
        ExecConfig config = ExecConfig.builder()
                .command(new String[] {
                        "psql",
                        "--no-psqlrc",
                        "--set=ON_ERROR_STOP=1",
                        "--username", POSTGRES.getUsername(),
                        "--dbname", POSTGRES.getDatabaseName(),
                        "--file", "/tmp/sentinelops-postgres/init/001_roles.sql"})
                .envVars(Map.of(
                        "PGPASSWORD", POSTGRES.getPassword(),
                        "SENTINELOPS_DB_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD,
                        "SENTINELOPS_DB_APP_PASSWORD", APP_PASSWORD))
                .build();
        var result = POSTGRES.execInContainer(config);
        assertEquals(0, result.getExitCode(), result.getStdout() + result.getStderr());
    }

    private static void verifyRuntimeDeleteExceptions(Connection app) throws SQLException {
        UUID principalId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement insertPrincipal = app.prepareStatement("""
                insert into principal(id, issuer, subject, display_name, created_at)
                values (?, 'https://task6.invalid', ?, 'Callback principal', ?)
                """)) {
            insertPrincipal.setObject(1, principalId);
            insertPrincipal.setString(2, principalId.toString());
            insertPrincipal.setTimestamp(3, now);
            assertEquals(1, insertPrincipal.executeUpdate());
        }
        try (PreparedStatement insertGrant = app.prepareStatement("""
                insert into role_grant(id, principal_id, role_name, granted_at)
                values (?, ?, 'observer', ?)
                """)) {
            insertGrant.setObject(1, grantId);
            insertGrant.setObject(2, principalId);
            insertGrant.setTimestamp(3, now);
            assertEquals(1, insertGrant.executeUpdate());
        }
        try (PreparedStatement deleteGrant = app.prepareStatement("delete from role_grant where id = ?")) {
            deleteGrant.setObject(1, grantId);
            assertEquals(1, deleteGrant.executeUpdate());
        }

        String source = "task6-callback-" + UUID.randomUUID();
        String nonceHash = "b".repeat(64);
        try (PreparedStatement insertNonce = app.prepareStatement("""
                insert into webhook_replay_nonce(source_key, nonce_hash, observed_at, expires_at)
                values (?, ?, ?, ?)
                """)) {
            insertNonce.setString(1, source);
            insertNonce.setString(2, nonceHash);
            insertNonce.setTimestamp(3, now);
            insertNonce.setTimestamp(4, Timestamp.from(now.toInstant().plusSeconds(60)));
            assertEquals(1, insertNonce.executeUpdate());
        }
        try (PreparedStatement deleteNonce = app.prepareStatement(
                "delete from webhook_replay_nonce where source_key = ? and nonce_hash = ?")) {
            deleteNonce.setString(1, source);
            deleteNonce.setString(2, nonceHash);
            assertEquals(0, deleteNonce.executeUpdate(),
                    "the afterMigrate callback must not let runtime credentials delete valid nonces");
        }

        assertPermissionDenied(app, "delete from service_catalog");
        assertPermissionDenied(app, "delete from principal");
        assertPermissionDenied(app, "delete from execution");
    }

    private static Connection adminConnection() throws SQLException {
        return connection(POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection appConnection() throws SQLException {
        return connection(APP_USER, APP_PASSWORD);
    }

    private static Connection connection(String username, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), username, password);
    }

    private static Connection connection(String jdbcUrl, String username, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static void assertPermissionDenied(Connection connection, String sql) throws SQLException {
        SQLException failure = assertThrows(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }, sql);
        assertNotNull(failure.getSQLState(), sql);
        assertEquals("42501", failure.getSQLState(), sql + " must fail with insufficient privilege");
    }

    private static Path projectRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("apps/ops-api/src/main/resources/db/migration"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find the SentinelOps worktree root from the test process");
    }
}
