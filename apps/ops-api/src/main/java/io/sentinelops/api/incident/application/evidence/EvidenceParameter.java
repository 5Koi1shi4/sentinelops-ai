package io.sentinelops.api.incident.application.evidence;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record EvidenceParameter(String validationRegex, Set<String> allowedValues) {
    public EvidenceParameter {
        Objects.requireNonNull(validationRegex, "validationRegex");
        allowedValues = Set.copyOf(allowedValues);
        if (validationRegex.isBlank() || validationRegex.length() > 256 || allowedValues.size() > 1000) {
            throw new IllegalArgumentException("invalid parameter rule");
        }
        Pattern.compile(validationRegex);
        if (allowedValues.stream().anyMatch(value -> !safeIdentifier(value))) {
            throw new IllegalArgumentException("parameter allowlist must contain identifiers");
        }
    }

    public boolean accepts(String value) {
        return safeIdentifier(value) && Pattern.matches(validationRegex, value)
                && (allowedValues.isEmpty() || allowedValues.contains(value));
    }

    private static boolean safeIdentifier(String value) {
        // 配置中的宽松正则也不能放行查询语法。
        return value != null && value.length() <= 128 && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }
}
