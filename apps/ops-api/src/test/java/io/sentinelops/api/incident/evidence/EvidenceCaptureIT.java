package io.sentinelops.api.incident.evidence;

import static org.assertj.core.api.Assertions.*;

import io.sentinelops.api.incident.adapter.out.persistence.EvidenceStore;
import io.sentinelops.api.incident.application.evidence.*;
import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class EvidenceCaptureIT extends PostgresIntegrationTest {
    private static final Instant FROM = Instant.parse("2026-09-22T00:00:00Z");
    private static final EvidenceBudget BUDGET = new EvidenceBudget(20, 4096, Duration.ofMinutes(15));
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    private EvidenceCaptureService capture;
    private EvidenceSource source;
    private UUID serviceId;
    private UUID principalId;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> message = new AtomicReference<>("password=hidden-one state=ok");
    private Runnable duringCapture = () -> {};

    @BeforeEach void setUp() {
        serviceId = jdbc.sql("select id from service_catalog where service_key='checkout-api'").query(UUID.class).single();
        principalId = jdbc.sql("select id from principal where subject='demo-author'").query(UUID.class).single();
        source = new EvidenceSource() {
            public String sourceType() { return "loki"; }
            public CapturedEvidence capture(EvidenceQuery query, EvidenceBudget budget) {
                calls.incrementAndGet();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                duringCapture.run();
                return CapturedEvidence.bounded(query, sourceType(), List.of(new CapturedEvidence.EvidenceItem(
                        "1790035200000000000", message.get(), Map.of("token", "label-credential"))),
                        List.of("Authorization: Bearer warning-credential"), budget);
            }
        };
        capture = new EvidenceCaptureService(List.of(source), new DefaultEvidenceRedactor(),
                new EvidenceStore(jdbc, mapper), mapper, transactions, new UuidV7Generator());
    }

    @Test void redactsBeforeHashingReusesSnapshotsAndLinksEveryRunWithoutChangingOriginalMetadata() {
        UUID incident = incident();
        UUID firstRun = run(incident);
        var first = freeze(incident, firstRun);
        message.set("password=hidden-two state=ok");
        UUID secondRun = run(incident);
        var reused = freeze(incident, secondRun);
        assertThat(reused.id()).isEqualTo(first.id());
        assertThat(reused.capturedAt()).isEqualTo(first.capturedAt());
        assertThat(reused.redactionCount()).isPositive();
        assertThat(reused.appliedRules()).isNotEmpty();
        assertThat(reused.payload().toString()).doesNotContain("hidden-one", "hidden-two", "label-credential", "warning-credential", "query-credential");
        assertThat(jdbc.sql("select query_spec::text from evidence_snapshot where id=:id").param("id", first.id())
                .query(String.class).single()).doesNotContain("query-credential");
        assertThat(jdbc.sql("select diagnosis_run_id from evidence_snapshot where id=:id").param("id", first.id())
                .query(UUID.class).single()).isEqualTo(firstRun);
        assertThat(count("evidence_snapshot", incident)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from diagnosis_run_evidence where evidence_snapshot_id=:id")
                .param("id", first.id()).query(Long.class).single()).isEqualTo(2);
        message.set("password=hidden-three state=failed");
        assertThat(freeze(incident, secondRun).id()).isNotEqualTo(first.id());
    }

    @Test void queryAndWindowRemainPartOfSnapshotIdentity() {
        UUID incident = incident();
        UUID run = run(incident);
        var first = freeze(incident, run);
        var queryChanged = capture.captureAndFreeze(incident, run,
                plan(serviceId, "other-query", FROM, FROM.plusSeconds(300))).getFirst();
        var windowChanged = capture.captureAndFreeze(incident, run,
                plan(serviceId, "logs", FROM.minusSeconds(1), FROM.plusSeconds(300))).getFirst();
        assertThat(first.id()).isNotEqualTo(queryChanged.id()).isNotEqualTo(windowChanged.id());
    }

    @Test void identicalValuesFromDifferentSourcesNeverShareIdentity() {
        UUID incident = incident();
        UUID run = run(incident);
        var first = freeze(incident, run);
        var other = new EvidenceSource() {
            public String sourceType() { return "prometheus"; }
            public CapturedEvidence capture(EvidenceQuery query, EvidenceBudget budget) {
                var captured = source.capture(query, budget);
                return CapturedEvidence.bounded(query, sourceType(), captured.items(), captured.warnings(), budget);
            }
        };
        var service = new EvidenceCaptureService(List.of(other), new DefaultEvidenceRedactor(),
                new EvidenceStore(jdbc, mapper), mapper, transactions, new UuidV7Generator());
        var second = service.captureAndFreeze(incident, run, new EvidencePlan(serviceId, FROM, FROM.plusSeconds(300),
                List.of(new EvidenceRequest("prometheus", "logs", Map.of("token", "query-credential"))), BUDGET)).getFirst();
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test void aLaterSourceFailureDoesNotPartiallyFreezeThePlan() {
        UUID incident = incident();
        UUID run = run(incident);
        var failing = new EvidenceSource() {
            public String sourceType() { return "prometheus"; }
            public CapturedEvidence capture(EvidenceQuery query, EvidenceBudget budget) {
                throw new EvidenceSourceException("provider unavailable");
            }
        };
        var service = new EvidenceCaptureService(List.of(source, failing), new DefaultEvidenceRedactor(),
                new EvidenceStore(jdbc, mapper), mapper, transactions, new UuidV7Generator());
        var plan = new EvidencePlan(serviceId, FROM, FROM.plusSeconds(300), List.of(
                new EvidenceRequest("loki", "logs", Map.of()), new EvidenceRequest("prometheus", "metrics", Map.of())), BUDGET);
        assertThatThrownBy(() -> service.captureAndFreeze(incident, run, plan)).isInstanceOf(EvidenceSourceException.class);
        assertThat(calls).hasValue(1);
        assertThat(count("evidence_snapshot", incident)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 20})
    void canonicalizesAgainAfterRedactionChangesRawSampleOrdering(int maxItems) {
        UUID incident = incident();
        UUID run = run(incident);
        var reverseSecrets = new java.util.concurrent.atomic.AtomicBoolean(false);
        var changing = new EvidenceSource() {
            public String sourceType() { return "loki"; }
            public CapturedEvidence capture(EvidenceQuery query, EvidenceBudget budget) {
                return CapturedEvidence.bounded(query, sourceType(), List.of(
                        new CapturedEvidence.EvidenceItem("1790035200000000000", "alpha",
                                Map.of("token", reverseSecrets.get() ? "z-secret" : "a-secret")),
                        new CapturedEvidence.EvidenceItem("1790035200000000000", "beta",
                                Map.of("token", reverseSecrets.get() ? "a-secret" : "z-secret"))), List.of(), budget);
            }
        };
        var service = new EvidenceCaptureService(List.of(changing), new DefaultEvidenceRedactor(),
                new EvidenceStore(jdbc, mapper), mapper, transactions, new UuidV7Generator());
        var plan = new EvidencePlan(serviceId, FROM, FROM.plusSeconds(300),
                List.of(new EvidenceRequest("loki", "logs", Map.of())),
                new EvidenceBudget(maxItems, BUDGET.maxBytes(), BUDGET.maxWindow()));
        var first = service.captureAndFreeze(incident, run, plan).getFirst();
        reverseSecrets.set(true);
        var second = service.captureAndFreeze(incident, run, plan).getFirst();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(count("evidence_snapshot", incident)).isEqualTo(1);
    }

    @Test void refusesEvidenceAlreadyTruncatedByTheServerAcquisitionCeiling() {
        UUID incident = incident();
        UUID run = run(incident);
        var incomplete = new EvidenceSource() {
            public String sourceType() { return "loki"; }
            public CapturedEvidence capture(EvidenceQuery query, EvidenceBudget budget) {
                return CapturedEvidence.create(query.incidentId(), query.serviceId(), sourceType(), query.queryId(),
                        query.from(), query.to(), List.of(), List.of(), true, budget.maxBytes());
            }
        };
        var service = new EvidenceCaptureService(List.of(incomplete), new DefaultEvidenceRedactor(),
                new EvidenceStore(jdbc, mapper), mapper, transactions, new UuidV7Generator());
        assertThatThrownBy(() -> service.captureAndFreeze(incident, run,
                plan(serviceId, "logs", FROM, FROM.plusSeconds(300))))
                .isInstanceOf(EvidenceBudgetExceeded.class);
        assertThat(count("evidence_snapshot", incident)).isZero();
    }

    @Test void rejectsForeignIncidentServiceAndCompletedRunBeforeCallingSource() {
        UUID incident = incident();
        UUID run = run(incident);
        assertThatThrownBy(() -> freeze(incident(), run)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capture.captureAndFreeze(incident, run,
                plan(UUID.randomUUID(), "logs", FROM, FROM.plusSeconds(300))))
                .isInstanceOf(IllegalArgumentException.class);
        complete(run);
        assertThatThrownBy(() -> freeze(incident, run)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void rechecksRunningStateAfterNetworkAndCommitsNoLateEvidence() {
        UUID incident = incident();
        UUID run = run(incident);
        duringCapture = () -> complete(run);
        assertThatThrownBy(() -> freeze(incident, run)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(1);
        assertThat(count("evidence_snapshot", incident)).isZero();
    }

    @Test void refusesCaptureInsideAnExistingTransactionBeforeSourceCalls() {
        UUID incident = incident();
        UUID run = run(incident);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> freeze(incident, run)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void rejectsStaleIncidentVersionBeforeAndAfterCapture() {
        UUID incident = incident();
        UUID run = run(incident);
        duringCapture = () -> jdbc.sql("update incident set version=version+1 where id=:id")
                .param("id", incident).update();
        assertThatThrownBy(() -> freeze(incident, run)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(1);
        assertThat(count("evidence_snapshot", incident)).isZero();
        assertThatThrownBy(() -> freeze(incident, run)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(1);
    }

    @Test void concurrentIdenticalCapturesProduceOneSnapshotAndTwoLinks() throws Exception {
        UUID incident = incident();
        UUID run1 = run(incident);
        UUID run2 = run(incident);
        var barrier = new CyclicBarrier(2);
        duringCapture = () -> {
            try { barrier.await(10, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> freeze(incident, run1));
            var second = executor.submit(() -> freeze(incident, run2));
            assertThat(first.get(20, TimeUnit.SECONDS).id()).isEqualTo(second.get(20, TimeUnit.SECONDS).id());
        }
        assertThat(count("evidence_snapshot", incident)).isEqualTo(1);
    }

    @Test void retrievalRequiresCurrentRunLinkAndReturnsDefensiveCopies() {
        UUID incident = incident();
        UUID run = run(incident);
        var frozen = freeze(incident, run);
        UUID unlinkedRun = run(incident);
        assertThatThrownBy(() -> capture.getEvidence(incident, unlinkedRun, serviceId, frozen.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capture.getEvidence(incident, run, UUID.randomUUID(), frozen.id()))
                .isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode) frozen.payload()).put("tampered", true);
        assertThat(frozen.payload().has("tampered")).isFalse();
        assertThat(capture.getEvidence(incident, run, serviceId, frozen.id()).payload().has("tampered")).isFalse();
    }

    @Test void databaseRejectsCrossIncidentRunLinksAndSnapshotOwnershipAndHistoryMutation() {
        UUID incident = incident();
        UUID run = run(incident);
        var frozen = freeze(incident, run);
        UUID otherIncident = incident();
        UUID otherRun = run(otherIncident);
        assertThatThrownBy(() -> jdbc.sql("insert into diagnosis_run_evidence values (:run,:evidence)")
                .param("run", otherRun).param("evidence", frozen.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                insert into evidence_snapshot(id,incident_id,diagnosis_run_id,source_type,source_ref,
                query_spec,redacted_payload,content_hash,captured_at)
                values (:id,:incident,:run,'loki','logs','{}','{}',:hash,now())
                """).param("id", UUID.randomUUID()).param("incident", incident).param("run", otherRun)
                .param("hash", UUID.randomUUID().toString()).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("update diagnosis_run set incident_id=:incident where id=:id")
                .param("incident", otherIncident).param("id", run).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("update evidence_snapshot set redacted_payload='{}' where id=:id")
                .param("id", frozen.id()).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.sql("delete from diagnosis_run_evidence where diagnosis_run_id=:id")
                .param("id", run).update()).isInstanceOf(Exception.class);
    }

    @Test void finalRedactedPayloadHonorsByteBudget() {
        UUID incident = incident();
        UUID run = run(incident);
        message.set("ordinary-value-".repeat(200));
        var frozen = capture.captureAndFreeze(incident, run,
                new EvidencePlan(serviceId, FROM, FROM.plusSeconds(300),
                        List.of(new EvidenceRequest("loki", "logs", Map.of())),
                        new EvidenceBudget(20, 600, Duration.ofMinutes(15)))).getFirst();
        assertThat(mapper.writeValueAsBytes(frozen.payload()).length).isLessThanOrEqualTo(600);
        assertThat(frozen.truncated()).isTrue();
        assertThat(frozen.contentHash()).matches("[a-f0-9]{64}");
    }

    private EvidenceSnapshot freeze(UUID incident, UUID run) {
        return capture.captureAndFreeze(incident, run, plan(serviceId, "logs", FROM, FROM.plusSeconds(300))).getFirst();
    }
    private EvidencePlan plan(UUID service, String query, Instant from, Instant to) {
        return new EvidencePlan(service, from, to,
                List.of(new EvidenceRequest("loki", query, Map.of("token", "query-credential"))), BUDGET);
    }
    private UUID incident() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into incident(id,service_id,fingerprint,title,severity,status,opened_at,updated_at)
                values (:id,:service,:fingerprint,'Evidence capture','sev2','triaging',now(),now())
                """).param("id", id).param("service", serviceId).param("fingerprint", id.toString()).update();
        return id;
    }
    private UUID run(UUID incident) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into diagnosis_run(id,incident_id,requested_by_principal_id,incident_version,
                engine_type,status,prompt_version,input_hash,started_at)
                values (:id,:incident,:principal,0,'deterministic','running','test','test',now())
                """).param("id", id).param("incident", incident).param("principal", principalId).update();
        return id;
    }
    private void complete(UUID run) {
        jdbc.sql("update diagnosis_run set status='succeeded',completed_at=now() where id=:id").param("id", run).update();
    }
    private long count(String table, UUID incident) {
        // Table is a test-owned constant, never external input.
        return jdbc.sql("select count(*) from " + table + " where incident_id=:id")
                .param("id", incident).query(Long.class).single();
    }
}
