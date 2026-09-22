package io.sentinelops.api.diagnosis.application.model;

import java.time.Duration;
import java.util.Objects;

@org.springframework.modulith.NamedInterface("evaluation")
public record ToolBudget(int maxTotalCalls, Duration maxDuration) {
    public ToolBudget {
        Objects.requireNonNull(maxDuration, "maxDuration");
        if (maxTotalCalls < 1 || maxTotalCalls > 6 || maxDuration.isNegative()
                || maxDuration.isZero() || maxDuration.compareTo(Duration.ofSeconds(90)) > 0) {
            throw new IllegalArgumentException("Tool budget exceeds server limits");
        }
    }
    public static ToolBudget defaults() { return new ToolBudget(6, Duration.ofSeconds(90)); }
}
