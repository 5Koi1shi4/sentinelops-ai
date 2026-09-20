package io.sentinelops.api.identity.application;

import java.util.Locale;
import java.util.Optional;

public enum PlatformRole {
    OBSERVER,
    ON_CALL_OPERATOR,
    SRE_APPROVER,
    RUNBOOK_ADMIN,
    PLATFORM_ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<PlatformRole> fromClaim(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
