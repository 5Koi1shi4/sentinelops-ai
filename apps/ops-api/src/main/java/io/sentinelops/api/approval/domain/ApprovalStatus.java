package io.sentinelops.api.approval.domain;

import java.util.Locale;

public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    INVALIDATED;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ApprovalStatus fromDatabase(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
