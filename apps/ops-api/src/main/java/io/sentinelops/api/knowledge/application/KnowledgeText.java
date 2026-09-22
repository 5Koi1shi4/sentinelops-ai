package io.sentinelops.api.knowledge.application;

/** Valid Unicode and code-point limits shared by the HTTP and storage boundaries. */
final class KnowledgeText {
    private KnowledgeText() {}

    static void require(String value, int maxCodePoints) {
        if (value == null || value.length() > 2L * maxCodePoints || value.isBlank()) {
            throw new IllegalArgumentException("text is empty or too long");
        }
        int points = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("text contains malformed Unicode");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("text contains malformed Unicode");
            }
            if (++points > maxCodePoints) throw new IllegalArgumentException("text is too long");
        }
    }
}
