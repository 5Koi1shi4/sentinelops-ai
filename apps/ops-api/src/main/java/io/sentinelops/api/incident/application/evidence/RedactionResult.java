package io.sentinelops.api.incident.application.evidence;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/** Immutable result of applying the evidence redaction policy. */
public final class RedactionResult {
    private final JsonNode json;
    private final int count;
    private final Set<String> appliedRules;
    private final boolean truncated;

    public RedactionResult(JsonNode json, int count, Set<String> appliedRules, boolean truncated) {
        this.json = Objects.requireNonNull(json, "json").deepCopy();
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        this.count = count;
        Objects.requireNonNull(appliedRules, "appliedRules");
        var sortedRules = new TreeSet<String>();
        for (String rule : appliedRules) {
            sortedRules.add(Objects.requireNonNull(rule, "appliedRules contains null"));
        }
        this.appliedRules = Collections.unmodifiableSet(sortedRules);
        this.truncated = truncated;
    }

    /** Returns a defensive deep copy of the redacted JSON tree. */
    public JsonNode json() {
        return json.deepCopy();
    }

    public int count() {
        return count;
    }

    public Set<String> appliedRules() {
        return appliedRules;
    }

    public boolean truncated() {
        return truncated;
    }
}
