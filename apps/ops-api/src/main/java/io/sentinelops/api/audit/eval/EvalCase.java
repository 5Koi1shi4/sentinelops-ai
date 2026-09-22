package io.sentinelops.api.audit.eval;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

public record EvalCase(String caseKey, JsonNode inputFixture, JsonNode expectation, List<String> tags) {
    public EvalCase {
        if (caseKey == null || !caseKey.matches("[a-z][a-z0-9-]{0,79}")) throw new IllegalArgumentException("Invalid case key");
        if (inputFixture == null || !inputFixture.isObject() || expectation == null || !expectation.isObject()) {
            throw new IllegalArgumentException("Eval input and expectation must be objects");
        }
        inputFixture = inputFixture.deepCopy();
        expectation = expectation.deepCopy();
        tags = List.copyOf(Objects.requireNonNull(tags));
        if (tags.isEmpty() || tags.size() > 20 || tags.stream().anyMatch(tag -> !tag.matches("[a-z][a-z0-9-]{0,79}"))) {
            throw new IllegalArgumentException("Invalid case tags");
        }
    }
    @Override public JsonNode inputFixture() { return inputFixture.deepCopy(); }
    @Override public JsonNode expectation() { return expectation.deepCopy(); }
}
