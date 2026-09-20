package io.sentinelops.api.diagnosis.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Hypothesis(
        int rank, String statement, BigDecimal confidence, List<UUID> evidenceRefs) {

    public Hypothesis {
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive");
        }
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("statement must not be blank");
        }
        Objects.requireNonNull(confidence, "confidence");
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    }
}
