package io.sentinelops.api.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;

import io.sentinelops.api.diagnosis.application.DiagnosisEngine;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposal;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import io.sentinelops.api.diagnosis.domain.Hypothesis;
import io.sentinelops.api.diagnosis.domain.VerificationExpectation;
import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.incident.domain.IncidentStatus;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import io.sentinelops.api.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

class DiagnosisFlowIT extends PostgresIntegrationTest {

    @Autowired private IncidentApplicationService incidents;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private MeterRegistry meters;
    @MockitoSpyBean private io.sentinelops.api.diagnosis.application.model.ModelGateway diagnosisEngine;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void diagnosesFromFrozenEvidenceAndReplaysWithoutDuplicatingHistory() throws Exception {
        double validCitationsBefore = citationChecks("success");
        String suffix = UUID.randomUUID().toString();
        var incident = incidents.ingest(new AlertEnvelope(
                "alertmanager",
                "diagnosis-alert-" + suffix,
                "checkout-api",
                "db-pool-" + suffix,
                "Checkout database pool is saturated",
                "sev1",
                AlertEnvelope.AlertStatus.FIRING,
                objectMapper.createObjectNode().put("status", "firing")));
        UUID metricEvidenceId = insertEvidence(
                incident.id(),
                "metric",
                "promql:db_pool_pending",
                objectMapper.createObjectNode().put("db_pool_pending", 3),
                "metric-" + suffix);
        UUID logEvidenceId = insertEvidence(
                incident.id(),
                "log",
                "loki:acquire-timeout",
                objectMapper.createObjectNode().put("acquire_timeout_count", 12),
                "log-" + suffix);

        var firstResult = mockMvc.perform(post("/api/v1/incidents/{id}/diagnosis-runs", incident.id())
                        .with(operator(incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "diagnose-" + suffix))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskLevel").value("R1"))
                .andReturn();
        var first = objectMapper.readValue(
                firstResult.getResponse().getContentAsString(), DiagnosisProposal.class);

        assertThat(first.riskLevel()).isEqualTo(RiskLevel.R1);
        assertThat(first.hypotheses().getFirst().evidenceRefs())
                .containsExactlyInAnyOrder(metricEvidenceId, logEvidenceId);
        assertThat(first.proposalHash()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(incidentStatus(incident.id())).isEqualTo(IncidentStatus.DIAGNOSED);
        assertThat(incidentVersion(incident.id())).isEqualTo(2);
        assertThat(diagnosisRunCount(incident.id())).isOne();
        assertThat(proposalCount(incident.id())).isOne();
        assertThat(runEvidenceCount(first.diagnosisRunId())).isEqualTo(2);
        assertThat(proposalEvidenceCount(first.id())).isEqualTo(2);
        assertThat(diagnosisEventCount(incident.id())).isEqualTo(2);
        assertThat(citationChecks("success") - validCitationsBefore).isEqualTo(1);

        var replayResult = mockMvc.perform(post("/api/v1/incidents/{id}/diagnosis-runs", incident.id())
                        .with(operator(incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "diagnose-" + suffix))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(replayResult.getResponse().getContentAsString())
                .isEqualTo(firstResult.getResponse().getContentAsString());
        assertThat(diagnosisRunCount(incident.id())).isOne();
        assertThat(proposalCount(incident.id())).isOne();
        assertThat(diagnosisEventCount(incident.id())).isEqualTo(2);
        assertThat(citationChecks("success") - validCitationsBefore).isEqualTo(1);

        mockMvc.perform(get("/api/v1/incidents/{id}/evidence", incident.id())
                        .with(operator(incident.serviceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].redactedPayload").exists())
                .andExpect(jsonPath("$[0].sourceRef").exists())
                .andExpect(jsonPath("$[0].contentHash").exists())
                .andExpect(jsonPath("$[0].capturedAt").exists())
                .andExpect(jsonPath("$[0].truncated").isBoolean())
                .andExpect(jsonPath("$[0].querySpec").doesNotExist());
    }

    @Test
    void rejectedProposalLeavesIncidentTriagingAndRecordsTypedFailure() throws Exception {
        double failedCitationsBefore = citationChecks("failure");
        String suffix = UUID.randomUUID().toString();
        var incident = incidents.ingest(new AlertEnvelope(
                "alertmanager",
                "invalid-diagnosis-alert-" + suffix,
                "checkout-api",
                "invalid-diagnosis-" + suffix,
                "Checkout database pool requires diagnosis",
                "sev2",
                AlertEnvelope.AlertStatus.FIRING,
                objectMapper.createObjectNode().put("status", "firing")));
        insertEvidence(
                incident.id(),
                "metric",
                "promql:db_pool_pending",
                objectMapper.createObjectNode().put("db_pool_pending", 3),
                "invalid-evidence-" + suffix);
        var invalidDraft = new DiagnosisProposalDraft(
                "Invalid evidence reference from the diagnosis engine.",
                List.of(new Hypothesis(
                        1,
                        "This hypothesis cites evidence outside the frozen context.",
                        new BigDecimal("0.90"),
                        List.of(UUID.randomUUID()))),
                List.of(),
                UUID.fromString("0199a000-0000-7000-8000-000000000005"),
                Map.of("replicas", 1),
                RiskLevel.R1,
                new VerificationExpectation(
                        "demo_checkout_health", new BigDecimal("1.0"), 6, 5));
        doReturn(new io.sentinelops.api.diagnosis.application.model.ModelDiagnosisResult(invalidDraft, "deterministic", "test", "diagnosis-system-v1", "test", "hash", "hash", 0, 0, 0, "stop", 0)).when(diagnosisEngine).diagnose(any());

        var failureResult = mockMvc.perform(post("/api/v1/incidents/{id}/diagnosis-runs", incident.id())
                        .with(operator(incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "invalid-diagnosis-" + suffix))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.errorCode").value("EVIDENCE_REFERENCE_UNKNOWN"))
                .andReturn();

        assertThat(incidentStatus(incident.id())).isEqualTo(IncidentStatus.TRIAGING);
        assertThat(incidentVersion(incident.id())).isOne();
        assertThat(diagnosisRunCount(incident.id())).isOne();
        assertThat(proposalCount(incident.id())).isZero();
        assertThat(diagnosisRunStatus(incident.id())).isEqualTo("failed");
        assertThat(runEvidenceCount(diagnosisRunId(incident.id()))).isOne();
        assertThat(lastDiagnosisEvent(incident.id())).isEqualTo("diagnosis_validation_failed");
        assertThat(citationChecks("failure") - failedCitationsBefore).isEqualTo(1);

        var failureReplay = mockMvc.perform(post("/api/v1/incidents/{id}/diagnosis-runs", incident.id())
                        .with(operator(incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "invalid-diagnosis-" + suffix))
                .andExpect(status().is(422))
                .andReturn();
        assertThat(citationChecks("failure") - failedCitationsBefore).isEqualTo(1);
        var firstFailureBody = objectMapper.readTree(
                failureResult.getResponse().getContentAsString());
        var replayFailureBody = objectMapper.readTree(
                failureReplay.getResponse().getContentAsString());
        assertThat(replayFailureBody.path("errorCode").asString())
                .isEqualTo(firstFailureBody.path("errorCode").asString());
        assertThat(replayFailureBody.path("detail").asString())
                .isEqualTo(firstFailureBody.path("detail").asString());
        assertThat(diagnosisRunCount(incident.id())).isOne();
        assertThat(diagnosisEventCount(incident.id())).isEqualTo(2);

        UUID timeoutEvidenceId = insertEvidence(
                incident.id(),
                "log",
                "loki:acquire-timeout",
                objectMapper.createObjectNode().put("acquire_timeout_count", 8),
                "retry-evidence-" + suffix);
        doCallRealMethod().when(diagnosisEngine).diagnose(any());

        var retryResult = mockMvc.perform(post("/api/v1/incidents/{id}/diagnosis-runs", incident.id())
                        .with(operator(incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", "retry-diagnosis-" + suffix))
                .andExpect(status().isCreated())
                .andReturn();
        var retriedProposal = objectMapper.readValue(
                retryResult.getResponse().getContentAsString(), DiagnosisProposal.class);

        assertThat(retriedProposal.hypotheses().getFirst().evidenceRefs())
                .contains(timeoutEvidenceId);
        assertThat(incidentStatus(incident.id())).isEqualTo(IncidentStatus.DIAGNOSED);
        assertThat(incidentVersion(incident.id())).isEqualTo(2);
        assertThat(diagnosisRunCount(incident.id())).isEqualTo(2);
        assertThat(proposalCount(incident.id())).isOne();
        assertThat(runEvidenceCount(retriedProposal.diagnosisRunId())).isEqualTo(2);
        assertThat(proposalEvidenceCount(retriedProposal.id())).isEqualTo(2);
        assertThat(diagnosisEventCount(incident.id())).isEqualTo(4);
    }

    @Test
    void staleIfMatchRollsBackTheIdempotencyClaimAndLeavesIncidentUntouched() throws Exception {
        String suffix = UUID.randomUUID().toString();
        var incident = incidents.ingest(new AlertEnvelope(
                "alertmanager",
                "stale-diagnosis-alert-" + suffix,
                "checkout-api",
                "stale-diagnosis-" + suffix,
                "Checkout diagnosis request is stale",
                "sev2",
                AlertEnvelope.AlertStatus.FIRING,
                objectMapper.createObjectNode().put("status", "firing")));
        String idempotencyKey = "stale-diagnosis-" + suffix;

        mockMvc.perform(post("/api/v1/incidents/{id}/diagnosis-runs", incident.id())
                        .with(operator(incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.errorCode").value("stale_resource_version"));

        assertThat(incidentStatus(incident.id())).isEqualTo(IncidentStatus.DETECTED);
        assertThat(incidentVersion(incident.id())).isZero();
        assertThat(diagnosisRunCount(incident.id())).isZero();
        assertThat(diagnosisEventCount(incident.id())).isZero();
        assertThat(idempotencyRecordCount(idempotencyKey)).isZero();
    }

    @Test
    void concurrentDistinctCommandsWithTheSameVersionDiagnoseExactlyOnce() throws Exception {
        String suffix = UUID.randomUUID().toString();
        var incident = incidents.ingest(new AlertEnvelope(
                "alertmanager",
                "concurrent-diagnosis-alert-" + suffix,
                "checkout-api",
                "concurrent-diagnosis-" + suffix,
                "Checkout database pool is saturated",
                "sev1",
                AlertEnvelope.AlertStatus.FIRING,
                objectMapper.createObjectNode().put("status", "firing")));
        insertEvidence(
                incident.id(),
                "metric",
                "promql:db_pool_pending",
                objectMapper.createObjectNode().put("db_pool_pending", 2),
                "concurrent-metric-" + suffix);
        insertEvidence(
                incident.id(),
                "log",
                "loki:acquire-timeout",
                objectMapper.createObjectNode().put("acquire_timeout_count", 5),
                "concurrent-log-" + suffix);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> diagnoseAfterLatch(
                    incident.id(), "concurrent-a-" + suffix, ready, start));
            var second = executor.submit(() -> diagnoseAfterLatch(
                    incident.id(), "concurrent-b-" + suffix, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                            first.get(10, TimeUnit.SECONDS),
                            second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 412);
            assertThat(incidentStatus(incident.id())).isEqualTo(IncidentStatus.DIAGNOSED);
            assertThat(diagnosisRunCount(incident.id())).isOne();
            assertThat(proposalCount(incident.id())).isOne();
            assertThat(diagnosisEventCount(incident.id())).isEqualTo(2);
            assertThat(idempotencyRecordCountWithPrefix("concurrent-", suffix)).isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private int diagnoseAfterLatch(
            UUID incidentId,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting to start diagnosis request");
        }
        return mockMvc.perform(post("/api/v1/incidents/{id}/diagnosis-runs", incidentId)
                        .with(operator(serviceId(incidentId)))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", idempotencyKey))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private RequestPostProcessor operator(UUID serviceId) {
        return jwt().authorities(
                        new SimpleGrantedAuthority(API_AUTHORITY),
                        new SimpleGrantedAuthority("ROLE_ON_CALL_OPERATOR"))
                .jwt(token -> token
                        .issuer("https://issuer.sentinelops.test")
                        .subject("diagnosis-test-operator")
                        .audience(List.of("sentinelops-api"))
                        .claim(
                                "realm_access",
                                Map.of("roles", List.of("on_call_operator")))
                        .claim("service_ids", List.of(serviceId.toString())));
    }

    private UUID serviceId(UUID incidentId) {
        return jdbc.sql("select service_id from incident where id = :incidentId")
                .param("incidentId", incidentId)
                .query(UUID.class)
                .single();
    }

    private UUID insertEvidence(
            UUID incidentId,
            String sourceType,
            String sourceRef,
            tools.jackson.databind.JsonNode payload,
            String contentHash) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into evidence_snapshot(
                          id, incident_id, source_type, source_ref, query_spec,
                          redacted_payload, content_hash, captured_at, truncated
                        ) values (
                          :id, :incidentId, :sourceType, :sourceRef, '{}'::jsonb,
                          cast(:payload as jsonb), :contentHash, :capturedAt, false
                        )
                        """)
                .param("id", id)
                .param("incidentId", incidentId)
                .param("sourceType", sourceType)
                .param("sourceRef", sourceRef)
                .param("payload", objectMapper.writeValueAsString(payload))
                .param("contentHash", contentHash)
                .param("capturedAt", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        return id;
    }

    private IncidentStatus incidentStatus(UUID incidentId) {
        return IncidentStatus.fromDatabase(jdbc.sql("select status from incident where id = :id")
                .param("id", incidentId)
                .query(String.class)
                .single());
    }

    private long incidentVersion(UUID incidentId) {
        return jdbc.sql("select version from incident where id = :id")
                .param("id", incidentId)
                .query(Long.class)
                .single();
    }

    private long diagnosisRunCount(UUID incidentId) {
        return jdbc.sql("select count(*) from diagnosis_run where incident_id = :id")
                .param("id", incidentId)
                .query(Long.class)
                .single();
    }

    private long proposalCount(UUID incidentId) {
        return jdbc.sql("select count(*) from diagnosis_proposal where incident_id = :id")
                .param("id", incidentId)
                .query(Long.class)
                .single();
    }

    private long diagnosisEventCount(UUID incidentId) {
        return jdbc.sql("""
                        select count(*) from incident_event
                        where incident_id = :id and event_type like 'diagnosis_%'
                        """)
                .param("id", incidentId)
                .query(Long.class)
                .single();
    }

    private String diagnosisRunStatus(UUID incidentId) {
        return jdbc.sql("select status from diagnosis_run where incident_id = :id")
                .param("id", incidentId)
                .query(String.class)
                .single();
    }

    private UUID diagnosisRunId(UUID incidentId) {
        return jdbc.sql("""
                        select id from diagnosis_run
                        where incident_id = :id
                        order by started_at, id
                        limit 1
                        """)
                .param("id", incidentId)
                .query(UUID.class)
                .single();
    }

    private long runEvidenceCount(UUID runId) {
        return jdbc.sql("""
                        select count(*) from diagnosis_run_evidence
                        where diagnosis_run_id = :runId
                        """)
                .param("runId", runId)
                .query(Long.class)
                .single();
    }

    private long proposalEvidenceCount(UUID proposalId) {
        return jdbc.sql("""
                        select count(*) from diagnosis_proposal_evidence
                        where proposal_id = :proposalId
                        """)
                .param("proposalId", proposalId)
                .query(Long.class)
                .single();
    }

    private long idempotencyRecordCount(String key) {
        return jdbc.sql("""
                        select count(*) from idempotency_record
                        where idempotency_key = :key
                        """)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    private long idempotencyRecordCountWithPrefix(String prefix, String suffix) {
        return jdbc.sql("""
                        select count(*) from idempotency_record
                        where idempotency_key like :pattern
                        """)
                .param("pattern", prefix + "%" + suffix)
                .query(Long.class)
                .single();
    }

    private String lastDiagnosisEvent(UUID incidentId) {
        return jdbc.sql("""
                        select event_type from incident_event
                        where incident_id = :id and event_type like 'diagnosis_%'
                        order by seq_no desc
                        limit 1
                        """)
                .param("id", incidentId)
                .query(String.class)
                .single();
    }

    private double citationChecks(String result) {
        var counter = meters.find("sentinelops.diagnosis.citation.validation")
                .tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
