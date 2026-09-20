package io.sentinelops.api.knowledge.domain;

import java.util.Locale;

public enum RiskLevel {
    R0,
    R1,
    R2,
    R3;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static RiskLevel fromDatabase(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
