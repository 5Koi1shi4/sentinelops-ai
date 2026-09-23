package io.sentinelops.api.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.sentinelops.api.diagnosis.application.DeterministicDiagnosisEngine;
import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisEvidence;
import io.sentinelops.api.knowledge.application.RunbookCatalog;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import io.sentinelops.api.knowledge.domain.RunbookVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DeterministicDiagnosisEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RunbookCatalog runbooks = mock(RunbookCatalog.class);
    private final DeterministicDiagnosisEngine engine =
            new DeterministicDiagnosisEngine(runbooks);

    @Test
    void recommendsTheRunbookOnlyWhenBothSignalsArePositive() {
        UUID serviceId = UUID.randomUUID();
        var pending = evidence("db_pool_pending", 4);
        var timeout = evidence("acquire_timeout_count", 2);
        var runbook = publishedRunbook(serviceId);
        when(runbooks.findPublished("RB-DB-POOL-03", serviceId))
                .thenReturn(Optional.of(runbook));

        var draft = engine.diagnose(new DiagnosisContext(
                UUID.randomUUID(), 0, serviceId, List.of(pending, timeout)));

        assertThat(draft.runbookVersionId()).isEqualTo(runbook.id());
        assertThat(draft.riskLevel()).isEqualTo(RiskLevel.R1);
        assertThat(draft.parameters()).containsEntry("replicas", 1);
        assertThat(draft.hypotheses().getFirst().evidenceRefs())
                .containsExactlyInAnyOrder(pending.id(), timeout.id());
    }

    @Test
    void returnsMissingEvidenceAndNoActionWhenEitherSignalIsAbsent() {
        var draft = engine.diagnose(new DiagnosisContext(
                UUID.randomUUID(),
                0,
                UUID.randomUUID(),
                List.of(evidence("db_pool_pending", 4))));

        assertThat(draft.runbookVersionId()).isNull();
        assertThat(draft.riskLevel()).isEqualTo(RiskLevel.R0);
        assertThat(draft.parameters()).isEmpty();
        assertThat(draft.missingEvidence()).containsExactly("acquire_timeout_count > 0");
        verifyNoInteractions(runbooks);
    }

    @Test
    void citesRealPrometheusAndLokiSnapshotsForThePublishedRecovery() {
        UUID serviceId = UUID.randomUUID();
        var pending = new DiagnosisEvidence(UUID.randomUUID(), "prometheus", "pool_pending",
                objectMapper.readTree("""
                        {"queryId":"pool_pending","items":[{"timestamp":"1780000000","value":"12","labels":{}}]}
                        """), "hash-pending", Instant.now(), false);
        var timeoutLog = new DiagnosisEvidence(UUID.randomUUID(), "loki", "acquire_timeout_logs",
                objectMapper.readTree("""
                        {"queryId":"acquire_timeout_logs","items":[{"timestamp":"1780000000000000000","value":"{\\"message\\":\\"Demo checkout acquire timeout\\"}","labels":{}}]}
                        """), "hash-timeout", Instant.now(), false);
        var runbook = publishedRunbook(serviceId);
        when(runbooks.findPublished("RB-DB-POOL-03", serviceId)).thenReturn(Optional.of(runbook));

        var draft = engine.diagnose(new DiagnosisContext(
                UUID.randomUUID(), 0, serviceId, List.of(pending, timeoutLog)));

        assertThat(draft.runbookVersionId()).isEqualTo(runbook.id());
        assertThat(draft.hypotheses().getFirst().evidenceRefs())
                .containsExactlyInAnyOrder(pending.id(), timeoutLog.id());
    }

    @Test
    void explainsPartialPoolEvidenceWithoutRecommendingRecovery() {
        var pending = evidence("db_pool_pending", 8);
        var draft = engine.diagnose(new DiagnosisContext(
                UUID.randomUUID(), 0, UUID.randomUUID(), List.of(pending)));

        assertThat(draft.runbookVersionId()).isNull();
        assertThat(draft.hypotheses().getFirst().statement()).containsIgnoringCase("pool saturation");
        assertThat(draft.hypotheses().getFirst().evidenceRefs()).containsExactly(pending.id());
        assertThat(draft.missingEvidence()).isNotEmpty();
    }

    @Test
    void distinguishesDownstreamTimeoutHighCpuAndConflictingPoolSignals() {
        for (var sample : List.of(
                Map.entry("{\"downstream_timeout_count\":18}", "downstream timeout"),
                Map.entry("{\"cpu_percent\":99}", "high cpu"),
                Map.entry("{\"db_pool_pending\":0,\"acquire_timeout_count\":10,\"log\":\"pool is healthy\"}",
                        "conflicting"))) {
            var evidence = new DiagnosisEvidence(UUID.randomUUID(), "prometheus", "service-health",
                    objectMapper.readTree(sample.getKey()), "hash", Instant.now(), false);
            var draft = engine.diagnose(new DiagnosisContext(
                    UUID.randomUUID(), 0, UUID.randomUUID(), List.of(evidence)));
            assertThat(draft.runbookVersionId()).isNull();
            assertThat(draft.hypotheses().getFirst().statement())
                    .containsIgnoringCase(sample.getValue());
            assertThat(draft.hypotheses().getFirst().evidenceRefs()).containsExactly(evidence.id());
        }
    }

    @Test
    void staleSignalsAndMissingPublishedRunbookFailClosed() {
        var stale = new DiagnosisEvidence(UUID.randomUUID(), "prometheus", "service-health",
                objectMapper.createObjectNode().put("db_pool_pending", 10)
                        .put("acquire_timeout_count", 5),
                "hash", Instant.parse("2026-09-19T00:00:00Z"), false);
        var context = new DiagnosisContext(UUID.randomUUID(), 0, UUID.randomUUID(), List.of(stale),
                UUID.randomUUID(), Instant.parse("2026-09-19T23:45:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"), "published-v1");
        var staleDraft = engine.diagnose(context);
        assertThat(staleDraft.runbookVersionId()).isNull();
        assertThat(staleDraft.riskLevel()).isEqualTo(RiskLevel.R0);

        var pending = evidence("db_pool_pending", 10);
        var timeout = evidence("acquire_timeout_count", 5);
        var retiredDraft = engine.diagnose(new DiagnosisContext(UUID.randomUUID(), 0,
                UUID.randomUUID(), List.of(pending, timeout)));
        assertThat(retiredDraft.runbookVersionId()).isNull();
        assertThat(retiredDraft.hypotheses().getFirst().evidenceRefs())
                .containsExactlyInAnyOrder(pending.id(), timeout.id());
        assertThat(retiredDraft.missingEvidence()).contains("published recovery Runbook");
    }

    private DiagnosisEvidence evidence(String field, int value) {
        return new DiagnosisEvidence(
                UUID.randomUUID(),
                "metric",
                "demo:" + field,
                objectMapper.createObjectNode().put(field, value),
                "hash-" + field,
                Instant.parse("2026-09-20T00:00:00Z"),
                false);
    }

    private RunbookVersion publishedRunbook(UUID serviceId) {
        return new RunbookVersion(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "RB-DB-POOL-03",
                serviceId,
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
                Instant.parse("2026-09-20T00:00:00Z"));
    }
}
