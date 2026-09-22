package io.sentinelops.api.audit.eval;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EvalDatasetTest {
    private final EvalDatasetImporter importer = new EvalDatasetImporter(new ObjectMapper());

    @Test void datasetContainsAllTwelveSafetyAndQualityCases() {
        var dataset = importer.builtin();
        assertThat(dataset.cases()).hasSize(12);
        assertThat(dataset.cases()).extracting(EvalCase::caseKey).doesNotHaveDuplicates()
                .contains("database-pool-exhaustion", "downstream-timeout", "high-cpu-no-runbook", "conflicting-evidence",
                        "missing-logs", "stale-evidence", "revoked-runbook", "unknown-service", "prompt-injection",
                        "explicit-shell", "invented-runbook", "source-timeout");
        assertThat(dataset.checksum()).matches("[a-f0-9]{64}");
        assertThat(dataset.cases().stream().flatMap(value -> value.tags().stream()).toList())
                .contains("prompt-injection", "dangerous-action");
    }

    @Test void duplicateCaseAndUnknownFieldsAreRejectedBeforePersistence() {
        var mapper = new ObjectMapper();
        var item = importer.builtin().cases().getFirst();
        String line = mapper.writeValueAsString(item);
        assertThatThrownBy(() -> importer.read(new ByteArrayInputStream((line+"\n"+line).getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
        var bad = mapper.valueToTree(item);
        ((tools.jackson.databind.node.ObjectNode) bad).put("executeShell", "blocked");
        assertThatThrownBy(() -> importer.read(new ByteArrayInputStream(mapper.writeValueAsBytes(bad))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void newlineOnlyDatasetIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> importer.read(new ByteArrayInputStream("\n\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fixtureMutationDoesNotAlterStoredDatasetIdentity() {
        var dataset = importer.builtin();
        var item = dataset.cases().getFirst();
        ((tools.jackson.databind.node.ObjectNode) item.inputFixture()).removeAll();
        assertThat(item.inputFixture().path("evidence").size()).isPositive();
    }
}
