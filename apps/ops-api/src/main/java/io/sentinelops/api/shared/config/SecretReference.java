package io.sentinelops.api.shared.config;

import org.springframework.core.env.Environment;

public final class SecretReference {
    private static final String ENV_REFERENCE = "env:[A-Z][A-Z0-9_]{0,127}";

    private SecretReference() {}

    public static String resolve(Environment environment, String reference, String purpose) {
        if (reference == null || !reference.matches(ENV_REFERENCE)) {
            throw new IllegalStateException(purpose + " requires an env secret reference");
        }
        var value = environment.getProperty(reference.substring(4));
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(purpose + " secret reference is unresolved");
        }
        return value;
    }
}
