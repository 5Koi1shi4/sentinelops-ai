package io.sentinelops.api.audit.eval;

import static org.assertj.core.api.Assertions.*;
import io.sentinelops.api.identity.application.*;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class EvalRunIT extends PostgresIntegrationTest {
    @Autowired EvalApplicationService evaluations;
    @Autowired JdbcClient jdbc;
    @Autowired tools.jackson.databind.ObjectMapper mapper;

    @Test void deterministicRunsAreReproduciblePersistedAndComparable() {
        var principal = admin();
        long incidentsBefore=jdbc.sql("select count(*) from incident").query(Long.class).single();
        long evidenceBefore=jdbc.sql("select count(*) from evidence_snapshot").query(Long.class).single();
        var first = evaluations.run(new EvalApplicationService.RunRequest("incidents-v1", null), UUID.randomUUID().toString(), principal);
        var second = evaluations.run(new EvalApplicationService.RunRequest("incidents-v1", first.id()), UUID.randomUUID().toString(), principal);
        assertThat(first.status()).isEqualTo("completed");
        assertThat(second.status()).isEqualTo("completed");
        assertThat(jdbc.sql("""
                        select coalesce(after_hash, '<missing>') from audit_record
                        where resource_type='eval_run' and resource_id=:run and action='eval_started'
                        """).param("run", first.id().toString()).query(String.class).single())
                .isEqualTo(first.datasetChecksum());
        assertThat(jdbc.sql("""
                        select metadata::text from audit_record
                        where resource_type='eval_run' and resource_id=:run and action='eval_started'
                        """).param("run", first.id().toString()).query(String.class).single())
                .doesNotContain("datasetChecksum");
        assertThat(first.results()).hasSize(12);
        assertThat(second.results()).extracting(EvalApplicationService.ResultView::scores)
                .containsExactlyElementsOf(first.results().stream().map(EvalApplicationService.ResultView::scores).toList());
        assertThat(second.results()).extracting(EvalApplicationService.ResultView::proposalHash)
                .containsExactlyElementsOf(first.results().stream().map(EvalApplicationService.ResultView::proposalHash).toList());
        assertThat(second.comparison()).isNotNull();
        assertThat(second.comparison().changedCaseKeys()).isEmpty();
        var firstProjection = stableProjection(first);
        var secondProjection = stableProjection(second);
        assertThat(canonicalUtf8(secondProjection))
                .containsExactly(canonicalUtf8(firstProjection));
        var changedProjection = (tools.jackson.databind.node.ObjectNode) firstProjection.deepCopy();
        var changedCase = (tools.jackson.databind.node.ObjectNode) changedProjection
                .path("cases").path(first.results().getFirst().caseKey());
        changedCase.put("inputTokens", changedCase.path("inputTokens").asLong() + 1);
        var changedScores = (tools.jackson.databind.node.ObjectNode) changedCase.path("scores");
        changedScores.put("citationResolvable", !changedScores.path("citationResolvable").asBoolean());
        assertThat(canonicalUtf8(changedProjection))
                .isNotEqualTo(canonicalUtf8(firstProjection));
        assertThat(evaluations.get(first.id(), principal).aggregateMetrics()).isEqualTo(first.aggregateMetrics());
        assertThat(jdbc.sql("select count(*) from eval_dataset").query(Long.class).single()).isOne();
        assertThat(jdbc.sql("select count(*) from incident").query(Long.class).single()).isEqualTo(incidentsBefore);
        assertThat(jdbc.sql("select count(*) from evidence_snapshot").query(Long.class).single()).isEqualTo(evidenceBefore);
        assertThat(jdbc.sql("select count(*) from diagnosis_run").query(Long.class).single()).isZero();
        var baseline = firstProjection;
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isDirectory(root.resolve("evals/datasets"))) root = root.getParent();
        assertThat(root).isNotNull();
        var path = root.resolve("evals/baselines/deterministic-v1.json");
        try {
            if (Boolean.getBoolean("eval.baseline.update")) {
                java.nio.file.Files.createDirectories(path.getParent());
                java.nio.file.Files.write(path, canonicalPrettyUtf8(baseline));
            }
            assertThat(java.nio.file.Files.exists(path)).as("Generate baseline with -Deval.baseline.update=true").isTrue();
            assertThat(java.nio.file.Files.readAllBytes(path))
                    .containsExactly(canonicalPrettyUtf8(baseline));
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private tools.jackson.databind.JsonNode stableProjection(EvalApplicationService.RunView run) {
        var projection = mapper.createObjectNode();
        projection.put("datasetChecksum", run.datasetChecksum());
        projection.set("configuration", run.runConfig().deepCopy());
        projection.put("releaseAllowed", Boolean.TRUE.equals(run.releaseAllowed()));

        var metrics = (tools.jackson.databind.node.ObjectNode) run.aggregateMetrics().deepCopy();
        metrics.remove("latencyMs");
        projection.set("aggregateMetrics", metrics);

        var cases = projection.putObject("cases");
        run.results().stream()
                .sorted(Comparator.comparing(EvalApplicationService.ResultView::caseKey))
                .forEach(result -> {
                    var value = cases.putObject(result.caseKey());
                    value.put("status", result.status());
                    value.put("proposalHash", result.proposalHash());
                    value.put("failureCode", result.failureCode());
                    value.set("scores", mapper.valueToTree(result.scores()));
                    value.put("inputTokens", result.inputTokens());
                    value.put("outputTokens", result.outputTokens());
                    value.put("costMicros", result.costMicros());
                });
        return projection;
    }

    private byte[] canonicalUtf8(tools.jackson.databind.JsonNode value) {
        return mapper.writeValueAsBytes(canonicalize(value));
    }

    private byte[] canonicalPrettyUtf8(tools.jackson.databind.JsonNode value) {
        var pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(canonicalize(value));
        return (fixedLf(pretty) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private tools.jackson.databind.JsonNode canonicalize(tools.jackson.databind.JsonNode value) {
        if (value == null) {
            return value;
        }
        if (value.isArray()) {
            var array = mapper.createArrayNode();
            value.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        if (!value.isObject()) {
            return value.deepCopy();
        }
        var object = mapper.createObjectNode();
        value.properties().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> object.set(entry.getKey(), canonicalize(entry.getValue())));
        return object;
    }

    private String fixedLf(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    @Test void replayReturnsSameRunAndDatabaseRejectsFrozenResultMutation() {
        var key = UUID.randomUUID().toString();
        var request = new EvalApplicationService.RunRequest("incidents-v1", null);
        var run = evaluations.run(request, key, admin());
        assertThat(evaluations.run(request, key, admin()).id()).isEqualTo(run.id());
        assertThatThrownBy(() -> jdbc.sql("update eval_case_result set scores='{}' where eval_run_id=:id")
                .param("id", run.id()).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("update eval_run set aggregate_metrics='{}' where id=:id")
                .param("id", run.id()).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("update eval_case set input_fixture='{}'").update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                insert into eval_case(id,dataset_id,case_key,input_fixture,expectation,tags,checksum)
                values(:id,:dataset,'late-case','{}','{}','{}',:hash)
                """).param("id",UUID.randomUUID()).param("dataset",run.datasetId()).param("hash",UUID.randomUUID().toString()).update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> evaluations.run(new EvalApplicationService.RunRequest("incidents-v1",run.id()),key,admin()))
                .isInstanceOfSatisfying(io.sentinelops.api.shared.problem.ApiProblemException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test void missingBaselineIsRejectedAndDeterministicQualityCannotApproveRelease() {
        var first=evaluations.run(new EvalApplicationService.RunRequest("incidents-v1",null),UUID.randomUUID().toString(),admin());
        assertThatThrownBy(()->evaluations.run(new EvalApplicationService.RunRequest("incidents-v1",UUID.randomUUID()),UUID.randomUUID().toString(),admin()))
                .isInstanceOfSatisfying(io.sentinelops.api.shared.problem.ApiProblemException.class,
                        failure->assertThat(failure.errorCode()).isEqualTo("EVAL_RUN_NOT_FOUND"));
        assertThat(first.releaseAllowed()).isFalse();
        assertThat(first.aggregateMetrics().path("runbookAccuracy").decimalValue()).isLessThan(new java.math.BigDecimal("0.85"));
        assertThat(first.aggregateMetrics().path("rootCauseTop3Accuracy").decimalValue()).isLessThan(new java.math.BigDecimal("0.80"));
    }

    @Test void operatorCannotCreateOrReadAnEvalRun() {
        var operator = new CurrentPrincipal("https://issuer.sentinelops.test", "eval-operator", Set.of(PlatformRole.ON_CALL_OPERATOR), Set.of());
        assertThatThrownBy(() -> evaluations.run(new EvalApplicationService.RunRequest("incidents-v1", null), "unauthorized", operator))
                .isInstanceOf(io.sentinelops.api.shared.problem.ApiProblemException.class);
        assertThatThrownBy(() -> evaluations.get(UUID.randomUUID(), operator))
                .isInstanceOf(io.sentinelops.api.shared.problem.ApiProblemException.class);
    }

    private CurrentPrincipal admin() {
        return new CurrentPrincipal("https://issuer.sentinelops.test", "eval-admin", Set.of(PlatformRole.PLATFORM_ADMIN), Set.of());
    }
}
