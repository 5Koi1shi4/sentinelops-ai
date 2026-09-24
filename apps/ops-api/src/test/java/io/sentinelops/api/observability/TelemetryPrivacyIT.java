package io.sentinelops.api.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.sentinelops.api.approval.application.ApprovalApplicationService;
import io.sentinelops.api.approval.application.ApprovalApplicationService.DecisionCommand;
import io.sentinelops.api.approval.application.ApprovalApplicationService.RequestContext;
import io.sentinelops.api.approval.domain.ApprovalDecision;
import io.sentinelops.api.diagnosis.application.DiagnosisApplicationService;
import io.sentinelops.api.execution.application.ExecutionApplicationService;
import io.sentinelops.api.execution.application.ExecutionApplicationService.CompletionCommand;
import io.sentinelops.api.execution.application.ExecutionApplicationService.AttemptPhaseCommand;
import io.sentinelops.api.execution.application.ExecutionVerificationService;
import io.sentinelops.api.execution.application.VerificationProbe;
import io.sentinelops.api.execution.application.VerificationProbe.VerificationResult;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.shared.observability.BusinessMetrics;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.boot.opentelemetry.autoconfigure.logging.SdkLoggerProviderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
@Import(TelemetryPrivacyIT.TelemetryTestConfig.class)
@TestPropertySource(properties = {
        "management.tracing.sampling.probability=1.0",
        "management.opentelemetry.tracing.export.otlp.enabled=false",
        "management.opentelemetry.logging.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
class TelemetryPrivacyIT extends PostgresIntegrationTest {

    private static final String PROMPT = "prompt-canary-6fd77917";
    private static final String EVIDENCE = "evidence-canary-76233b2a";
    private static final String BEARER = "bearer-canary-c7795d11";
    private static final String COMMENT = "approval-canary-fcb5376d";

    @Autowired private IncidentApplicationService incidents;
    @Autowired private DiagnosisApplicationService diagnoses;
    @Autowired private ApprovalApplicationService approvals;
    @Autowired private ExecutionApplicationService executions;
    @Autowired private ExecutionVerificationService verification;
    @Autowired private ObservationRegistry observations;
    @Autowired private BusinessMetrics businessMetrics;
    @Autowired private MeterRegistry meters;
    @Autowired private InMemorySpanExporter spans;
    @Autowired private InMemoryLogRecordExporter otelLogs;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private WebApplicationContext web;
    @MockitoBean private VerificationProbe probe;

    @Test
    void customObservationsAndMetersRejectUntrustedAttributes() {
        spans.reset();
        otelLogs.reset();
        UUID incidentId = UUID.randomUUID();
        Observation.createNotStarted("sentinelops.privacy.probe", observations)
                .lowCardinalityKeyValue("result", COMMENT)
                .lowCardinalityKeyValue("prompt", PROMPT)
                .highCardinalityKeyValue("authorization", BEARER)
                .highCardinalityKeyValue("incident.id", incidentId.toString())
                .start().stop();
        meters.counter("sentinelops.privacy.probe", "prompt", PROMPT,
                "result", COMMENT).increment();

        String exported = spans.getFinishedSpanItems().toString()
                + meters.getMeters().stream().map(meter -> meter.getId().toString()).toList();
        assertThat(exported).contains(incidentId.toString())
                .doesNotContain(PROMPT, BEARER, COMMENT, "authorization");
    }

    @Test
    void repeatedCorrelationAssignmentsRestoreTheOriginalMdc() {
        UUID original = UUID.randomUUID();
        MDC.put("incident.id", original.toString());
        try {
            try (var scope = businessMetrics.start(BusinessMetrics.Operation.INCIDENT_INGEST)) {
                scope.incident(UUID.randomUUID()).incident(UUID.randomUUID()).success();
            }
            assertThat(MDC.get("incident.id")).isEqualTo(original.toString());
            MDC.remove("incident.id");
            try (var scope = businessMetrics.start(BusinessMetrics.Operation.INCIDENT_INGEST)) {
                scope.incident(UUID.randomUUID()).incident(UUID.randomUUID()).success();
            }
            assertThat(MDC.get("incident.id")).isNull();
        } finally {
            MDC.remove("incident.id");
        }
    }

    @Test
    void rolledBackAlertDoesNotCountAsDetection() {
        double before = meterCount("sentinelops.incident.detected");
        String suffix = UUID.randomUUID().toString();
        var alert = new AlertEnvelope("alertmanager", suffix, "checkout-api", suffix,
                "Rollback check", "sev2", AlertEnvelope.AlertStatus.FIRING,
                mapper.createObjectNode().put("status", "firing"));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            incidents.ingest(alert);
            status.setRollbackOnly();
        });

        assertThat(meterCount("sentinelops.incident.detected")).isEqualTo(before);
    }

    @Test
    void normalizedAlertmanagerPayloadStillRecordsDetectionDelay() {
        long before = meters.find("sentinelops.incident.mttd").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
        String suffix = UUID.randomUUID().toString();
        var alert = new AlertEnvelope("alertmanager", suffix, "checkout-api", suffix,
                "MTTD check", "sev2", AlertEnvelope.AlertStatus.FIRING,
                mapper.createObjectNode().put("startsAt", Instant.now().minusSeconds(60).toString()));

        incidents.ingest(alert);

        assertThat(meters.find("sentinelops.incident.mttd").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum() - before).isEqualTo(1);
    }

    @Test
    void signedAlertmanagerSourceAliasRecordsDetectionDelay() {
        long before = meters.find("sentinelops.incident.mttd").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
        String suffix = UUID.randomUUID().toString();
        var payload = mapper.createObjectNode().put("version", "4");
        payload.set("alerts", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("startsAt", Instant.now().minusSeconds(60).toString())));
        var alert = new AlertEnvelope("demo-alertmanager", suffix, "checkout-api", suffix,
                "Source alias MTTD check", "sev2", AlertEnvelope.AlertStatus.FIRING, payload);

        incidents.ingest(alert);

        assertThat(meters.find("sentinelops.incident.mttd").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum() - before).isEqualTo(1);
    }

    @Test
    void aCompleteIncidentWorkflowExportsOnlyCorrelationsAndBoundedBusinessTags(CapturedOutput logs)
            throws Exception {
        spans.reset();
        otelLogs.reset();
        UUID serviceId = jdbc.sql("select id from service_catalog where service_key='checkout-api'")
                .query(UUID.class).single();
        RequestContext requester = principal(serviceId, PlatformRole.ON_CALL_OPERATOR);
        RequestContext approver = principal(serviceId, PlatformRole.SRE_APPROVER);
        String suffix = UUID.randomUUID().toString();

        var root = Observation.createNotStarted("sentinelops.test.workflow", observations).start();
        UUID incidentId;
        UUID runId;
        UUID approvalId;
        UUID executionId;
        String outboxTraceparent;
        String executionTicket;
        try (var scope = root.openScope()) {
            var startedAt = Instant.now().minusSeconds(30);
            var alertPayload = mapper.createObjectNode().put("status", "firing");
            alertPayload.set("alerts", mapper.createArrayNode()
                    .add(mapper.createObjectNode().put("startsAt", startedAt.toString())));
            var alert = new AlertEnvelope(
                    "alertmanager", "telemetry-" + suffix, "checkout-api", "telemetry-" + suffix,
                    PROMPT, "sev2", AlertEnvelope.AlertStatus.FIRING,
                    alertPayload);
            double detectedBefore = meterCount("sentinelops.incident.detected");
            var incident = incidents.ingest(alert);
            incidents.ingest(alert);
            assertThat(meterCount("sentinelops.incident.detected") - detectedBefore)
                    .isEqualTo(1);
            incidentId = incident.id();
            insertEvidence(incidentId, EVIDENCE);

            var proposal = diagnoses.diagnose(incidentId, 0, "diagnose-" + suffix,
                    requester.principal(), requester.principalId());
            runId = proposal.diagnosisRunId();
            double diagnosisCompletedBeforeReplay = meterCount("sentinelops.diagnosis.completed");
            assertThat(diagnoses.diagnose(incidentId, 0, "diagnose-" + suffix,
                    requester.principal(), requester.principalId()).id()).isEqualTo(proposal.id());
            assertThat(meterCount("sentinelops.diagnosis.completed")
                    - diagnosisCompletedBeforeReplay).isZero();
            var requested = approvals.request(incidentId, proposal.id(), 2,
                    "request-" + suffix, requester);
            approvalId = requested.id();
            var decided = approvals.decide(approvalId, requested.incidentVersion(),
                    "approve-" + suffix,
                    new DecisionCommand(ApprovalDecision.APPROVE, COMMENT, proposal.proposalHash()),
                    approver);
            var created = executions.create(incidentId, proposal.id(), decided.incidentVersion(),
                    "execute-" + suffix, requester.principal());
            executionId = created.id();
            outboxTraceparent = jdbc.sql("""
                    select payload->>'traceparent' from outbox_event
                    where aggregate_id = :executionId and event_type = 'execution.requested.v1'
                    """).param("executionId", executionId).query(String.class).single();
            assertThat(outboxTraceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
            var claim = executions.claim(executionId, "telemetry-executor");
            executionTicket = claim.ticket();
            double leaseConflictsBefore = meterCount("sentinelops.execution.lease.conflicts");
            assertThatThrownBy(() -> executions.claim(executionId, "other-executor"))
                    .isInstanceOf(ApiProblemException.class);
            assertThat(meterCount("sentinelops.execution.lease.conflicts")
                    - leaseConflictsBefore).isEqualTo(1);
            double fencingConflictsBefore = meterCount("sentinelops.execution.fencing.conflicts");
            assertThatThrownBy(() -> executions.complete(executionId, "other-executor",
                    claim.fencingToken(), executionTicket,
                    new CompletionCommand("recover-one", 1, "demo-http",
                            "demo-http-v1", "request-hash", Map.of("changed", true))))
                    .isInstanceOf(ApiProblemException.class);
            assertThat(meterCount("sentinelops.execution.fencing.conflicts")
                    - fencingConflictsBefore).isEqualTo(1);
            executions.recordAttemptPhase(executionId, "telemetry-executor",
                    claim.fencingToken(), executionTicket,
                    new AttemptPhaseCommand("recover-one", 1, "prepared", Map.of()),
                    "executor:telemetry", "prepared-" + suffix);
            executions.recordAttemptPhase(executionId, "telemetry-executor",
                    claim.fencingToken(), executionTicket,
                    new AttemptPhaseCommand("recover-one", 1, "dispatched", Map.of()),
                    "executor:telemetry", "dispatched-" + suffix);
            var completion = new CompletionCommand("recover-one", 1, "demo-http",
                    "demo-http-v1", "request-hash", Map.of("changed", true));
            double outcomesBefore = meterCount("sentinelops.execution.outcomes");
            executions.complete(executionId, "telemetry-executor", claim.fencingToken(),
                    executionTicket, completion, "executor:telemetry", "complete-" + suffix);
            executions.complete(executionId, "telemetry-executor", claim.fencingToken(),
                    executionTicket, completion, "executor:telemetry", "complete-" + suffix);
            assertThat(meterCount("sentinelops.execution.outcomes") - outcomesBefore)
                    .isEqualTo(1);
            when(probe.verify(any())).thenReturn(VerificationResult.succeeded(
                    Map.of("status", "UP", "httpStatus", 200)));
            assertThat(verification.runPendingOnce()).isTrue();

            MockMvcBuilders.webAppContextSetup(web).build()
                    .perform(get("/actuator/health/liveness")
                            .header("Authorization", "Bearer " + BEARER))
                    .andExpect(status().isOk());
        } finally {
            root.stop();
        }

        List<SpanData> exported = spans.getFinishedSpanItems();
        assertThat(exported).extracting(SpanData::getName)
                .contains("sentinelops.incident.ingest", "sentinelops.diagnosis.run",
                        "sentinelops.approval.request", "sentinelops.approval.decide",
                        "sentinelops.execution.claim", "sentinelops.execution.complete",
                        "sentinelops.verification.run");
        String telemetry = exported.toString() + meters.getMeters().stream()
                .map(meter -> meter.getId().toString()).toList() + logs.getAll();
        assertThat(telemetry).doesNotContain(PROMPT, EVIDENCE, BEARER, COMMENT, executionTicket);
        assertThat(exported.toString()).contains(incidentId.toString(), runId.toString(),
                approvalId.toString(), executionId.toString());
        String workflowTrace = exported.stream()
                .filter(span -> span.getName().equals("sentinelops.test.workflow"))
                .findFirst().orElseThrow().getTraceId();
        assertThat(outboxTraceparent).contains(workflowTrace);
        assertThat(exported.stream()
                .filter(span -> span.getTraceId().equals(workflowTrace))
                .map(SpanData::getName))
                .contains("sentinelops.incident.ingest", "sentinelops.diagnosis.run",
                        "sentinelops.approval.request", "sentinelops.approval.decide",
                        "sentinelops.execution.claim", "sentinelops.execution.complete",
                        "sentinelops.verification.run");
        assertThat(otelLogs.getFinishedLogRecordItems()).anySatisfy(record -> {
            assertThat(record.getAttributes().get(AttributeKey.stringKey("operation")))
                    .isEqualTo("sentinelops.execution.complete");
            assertThat(record.getAttributes().get(AttributeKey.stringKey("execution.id")))
                    .isEqualTo(executionId.toString());
            assertThat(record.getSpanContext().getTraceId()).isEqualTo(workflowTrace);
        });
        assertThat(otelLogs.getFinishedLogRecordItems().toString())
                .doesNotContain(PROMPT, EVIDENCE, BEARER, COMMENT, executionTicket);
        assertThat(meters.getMeters().stream().map(meter -> meter.getId().getName()))
                .contains("sentinelops.incident.detected", "sentinelops.diagnosis.completed",
                        "sentinelops.approval.decisions", "sentinelops.execution.outcomes",
                        "sentinelops.incident.mttd", "sentinelops.incident.mttr");
    }

    private double meterCount(String name) {
        return meters.find(name).counters().stream().mapToDouble(counter -> counter.count()).sum();
    }

    private RequestContext principal(UUID serviceId, PlatformRole role) {
        UUID id = UUID.randomUUID();
        var principal = new CurrentPrincipal("https://issuer.sentinelops.test",
                "telemetry-" + role + '-' + id, Set.of(role), Set.of(serviceId));
        jdbc.sql("""
                insert into principal(id,issuer,subject,display_name,created_at)
                values(:id,:issuer,:subject,:subject,clock_timestamp())
                """).param("id", id).param("issuer", principal.issuer())
                .param("subject", principal.subject()).update();
        return new RequestContext(principal, id);
    }

    private void insertEvidence(UUID incidentId, String content) {
        insertEvidence(incidentId, "metric", "promql:db_pool_pending",
                Map.of("db_pool_pending", 3));
        insertEvidence(incidentId, "log", "loki:acquire-timeout",
                Map.of("acquire_timeout_count", 12, "message", content));
    }

    private void insertEvidence(UUID incidentId, String sourceType, String sourceRef,
            Map<String, Object> payload) {
        jdbc.sql("""
                insert into evidence_snapshot(id,incident_id,source_type,source_ref,query_spec,
                  redacted_payload,content_hash,captured_at,truncated)
                values(:id,:incidentId,:sourceType,:sourceRef,'{}'::jsonb,
                  cast(:payload as jsonb),:hash,:capturedAt,false)
                """).param("id", UUID.randomUUID()).param("incidentId", incidentId)
                .param("sourceType", sourceType).param("sourceRef", sourceRef)
                .param("payload", mapper.writeValueAsString(payload))
                .param("hash", "telemetry-" + UUID.randomUUID())
                .param("capturedAt", OffsetDateTime.now(ZoneOffset.UTC)).update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TelemetryTestConfig {
        @Bean InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean SdkTracerProviderBuilderCustomizer captureSpans(InMemorySpanExporter exporter) {
            return builder -> builder.addSpanProcessor(SimpleSpanProcessor.create(exporter));
        }

        @Bean InMemoryLogRecordExporter inMemoryLogRecordExporter() {
            return InMemoryLogRecordExporter.create();
        }

        @Bean SdkLoggerProviderBuilderCustomizer captureLogs(InMemoryLogRecordExporter exporter) {
            return builder -> builder.addLogRecordProcessor(
                    SimpleLogRecordProcessor.create(exporter));
        }
    }
}
