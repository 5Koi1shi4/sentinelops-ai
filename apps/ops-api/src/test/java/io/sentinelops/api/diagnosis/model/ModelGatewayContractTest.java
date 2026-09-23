package io.sentinelops.api.diagnosis.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import io.sentinelops.api.diagnosis.adapter.out.model.DeterministicModelGateway;
import io.sentinelops.api.diagnosis.application.DeterministicDiagnosisEngine;
import io.sentinelops.api.diagnosis.application.model.ModelDiagnosisRequest;
import io.sentinelops.api.diagnosis.application.model.ToolBudget;
import io.sentinelops.api.diagnosis.application.tool.ToolContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisEvidence;
import io.sentinelops.api.diagnosis.application.tool.EvidenceTools;
import io.sentinelops.api.incident.application.evidence.EvidenceBudget;
import io.sentinelops.api.incident.application.evidence.EvidenceCapture;
import io.sentinelops.api.incident.application.evidence.EvidencePlan;
import io.sentinelops.api.incident.application.evidence.EvidenceSnapshot;
import io.sentinelops.api.knowledge.application.RunbookCatalog;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import io.sentinelops.api.knowledge.domain.RunbookVersion;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ModelGatewayContractTest {

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID SERVICE_ID = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-22T00:05:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deterministicGatewayReturnsStructuredProposalAndMetadata() {
        var runbooks = mock(RunbookCatalog.class);
        var runbook = publishedRunbook();
        when(runbooks.findPublished("RB-DB-POOL-03", SERVICE_ID)).thenReturn(Optional.of(runbook));
        var gateway = new DeterministicModelGateway(new DeterministicDiagnosisEngine(runbooks));

        var result = gateway.diagnose(request());

        assertThat(result.proposal().runbookVersionId()).isEqualTo(runbook.id());
        assertThat(result.provider()).isEqualTo("deterministic");
        assertThat(result.modelName()).isEqualTo("deterministic-v1");
        assertThat(result.promptVersion()).isEqualTo("diagnosis-system-v1");
        assertThat(result.runbookCorpusVersion()).isEqualTo("published-v1");
        assertThat(result.toolCallCount()).isZero();
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.inputHash()).isNotBlank();
        assertThat(result.responseHash()).isNotBlank();
    }

    @Test
    void realEvidenceGatewayCapturesRegisteredQueriesAndCitesFrozenSnapshots() {
        var runbooks = mock(RunbookCatalog.class);
        var runbook = publishedRunbook();
        when(runbooks.findPublished("RB-DB-POOL-03", SERVICE_ID)).thenReturn(Optional.of(runbook));
        var capture = mock(EvidenceCapture.class);
        when(capture.captureAndFreeze(any(), any(), any())).thenAnswer(invocation -> {
            EvidencePlan plan = invocation.getArgument(2);
            var query = plan.requests().getFirst();
            var payload = switch (query.queryId()) {
                case "pool_pending" -> objectMapper.readTree("""
                        {"items":[{"timestamp":"1780000000","value":"12","labels":{}}]}
                        """);
                case "acquire_timeout_logs" -> objectMapper.readTree("""
                        {"items":[{"timestamp":"1780000000000000000","value":"Demo checkout acquire timeout","labels":{}}]}
                        """);
                default -> objectMapper.readTree("{" + "\"items\":[]}");
            };
            return List.of(new EvidenceSnapshot(UUID.randomUUID(), query.sourceType(), query.queryId(),
                    TO, "a".repeat(64), false, 0, Set.of(), payload));
        });
        var gateway = new DeterministicModelGateway(new DeterministicDiagnosisEngine(runbooks),
                new EvidenceTools(capture, new EvidenceBudget(200, 128 * 1024, Duration.ofMinutes(15))),
                objectMapper);

        var context = new DiagnosisContext(INCIDENT_ID, 1, SERVICE_ID, List.of(),
                RUN_ID, FROM, TO, "published-v1");
        var result = gateway.diagnose(new ModelDiagnosisRequest(context,
                new ToolContext(INCIDENT_ID, RUN_ID, SERVICE_ID, FROM, TO),
                "diagnosis-system-v1", ToolBudget.defaults()));

        assertThat(result.proposal().runbookVersionId()).isEqualTo(runbook.id());
        assertThat(result.proposal().hypotheses().getFirst().evidenceRefs()).hasSize(2);
        assertThat(result.toolCallCount()).isEqualTo(4);
        verify(capture, times(4)).captureAndFreeze(any(), any(), any());
    }

    @Test
    void requestRejectsToolContextThatDoesNotMatchFrozenRun() {
        var context = diagnosisContext();
        var mismatchedTools = new ToolContext(
                context.incidentId(), UUID.randomUUID(), context.serviceId(), FROM, TO);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelDiagnosisRequest(
                        context, mismatchedTools, "diagnosis-system-v1", ToolBudget.defaults()));
    }

    @Test
    void budgetCannotBeRaisedAboveSixCallsOrNinetySeconds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ToolBudget(7, Duration.ofSeconds(90)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ToolBudget(6, Duration.ofSeconds(91)));
        assertThat(ToolBudget.defaults().maxTotalCalls()).isEqualTo(6);
        assertThat(ToolBudget.defaults().maxDuration()).isEqualTo(Duration.ofSeconds(90));
    }

    private ModelDiagnosisRequest request() {
        var context = diagnosisContext();
        return new ModelDiagnosisRequest(
                context,
                new ToolContext(INCIDENT_ID, RUN_ID, SERVICE_ID, FROM, TO),
                "diagnosis-system-v1",
                ToolBudget.defaults());
    }

    private DiagnosisContext diagnosisContext() {
        return new DiagnosisContext(
                INCIDENT_ID,
                1,
                SERVICE_ID,
                List.of(
                        new DiagnosisEvidence(
                                UUID.randomUUID(),
                                "metric",
                                "prometheus:db_pool_pending",
                                objectMapper.createObjectNode().put("db_pool_pending", 4),
                                "hash-pending",
                                FROM.plusSeconds(30),
                                false),
                        new DiagnosisEvidence(
                                UUID.randomUUID(),
                                "log",
                                "loki:acquire_timeout_count",
                                objectMapper.createObjectNode().put("acquire_timeout_count", 2),
                                "hash-timeout",
                                FROM.plusSeconds(45),
                                false)),
                RUN_ID,
                FROM,
                TO,
                "published-v1");
    }

    private RunbookVersion publishedRunbook() {
        return new RunbookVersion(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "RB-DB-POOL-03",
                SERVICE_ID,
                1,
                RunbookVersion.Lifecycle.PUBLISHED,
                RiskLevel.R1,
                "demo-http",
                objectMapper.readTree("""
                        {
                          "verification": {
                            "probe": "demo_checkout_health",
                            "successThreshold": 1.0,
                            "attempts": 6,
                            "intervalSeconds": 5
                          }
                        }
                        """),
                "checksum",
                Instant.parse("2026-09-22T00:00:00Z"));
    }
}
