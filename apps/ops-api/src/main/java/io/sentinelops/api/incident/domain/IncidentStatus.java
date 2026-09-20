package io.sentinelops.api.incident.domain;

import java.util.Locale;

public enum IncidentStatus {
    DETECTED,
    TRIAGING,
    DIAGNOSED,
    AWAITING_APPROVAL,
    EXECUTING,
    VERIFYING,
    RESOLVED,
    SUPPRESSED,
    ESCALATED;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static IncidentStatus fromDatabase(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
