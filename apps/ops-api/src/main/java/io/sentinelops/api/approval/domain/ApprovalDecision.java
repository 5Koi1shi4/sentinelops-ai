package io.sentinelops.api.approval.domain;

import java.util.Locale;

public enum ApprovalDecision {
    APPROVE,
    REJECT;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ApprovalDecision fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("decision must not be blank");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
