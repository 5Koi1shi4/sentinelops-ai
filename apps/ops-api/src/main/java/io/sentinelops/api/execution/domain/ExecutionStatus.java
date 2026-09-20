package io.sentinelops.api.execution.domain;

import java.util.Locale;

public enum ExecutionStatus {
    PENDING,
    RUNNING,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    ESCALATED;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ExecutionStatus fromDatabase(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
