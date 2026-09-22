package io.sentinelops.api.knowledge.application;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeQueryTest {
    private KnowledgeQuery query(String text) {
        var vector = new float[1536]; vector[0] = 1;
        return new KnowledgeQuery(UUID.randomUUID(), text, vector, "test-v1");
    }
    @Test void acceptsSupplementaryCharactersWithinCodePointBudget() {
        assertThat(query("🙂".repeat(1000)).text().codePointCount(0, 2000)).isEqualTo(1000);
        assertThatThrownBy(() -> query("🙂".repeat(1001))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMalformedSurrogatesBeforeEmbeddingOrSql() {
        assertThatThrownBy(() -> query("broken\uD800text")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("broken\uDC00text")).isInstanceOf(IllegalArgumentException.class);
    }
}
