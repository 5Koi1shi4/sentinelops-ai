package io.sentinelops.api.incident.application.evidence;

import java.util.Collections;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Deterministically redacts untrusted evidence JSON before it can be persisted or hashed.
 *
 * <p>The redactor creates a new tree for every call. It never mutates the supplied tree, and
 * denylist and size/depth replacements remove complete values; credential patterns replace only
 * the matched secret span so safe diagnostic context remains available without leaking secrets.
 */
public final class DefaultEvidenceRedactor implements EvidenceRedactor {
    public static final int DEFAULT_MAX_STRING_LENGTH = 4_096;
    public static final int DEFAULT_MAX_DEPTH = 8;
    public static final int MAX_STRING_LENGTH = 65_536;
    public static final int MAX_DEPTH = 32;

    private static final int MAX_DENIED_POINTERS = 1_024;
    private static final int MAX_POINTER_LENGTH = 1_024;
    private static final Set<String> DEFAULT_DENIED_JSON_POINTERS = Set.of(
            "/headers/authorization",
            "/headers/cookie",
            "/headers/set-cookie",
            "/request/headers/authorization",
            "/request/headers/cookie",
            "/queryMetadata/authorization",
            "/queryMetadata/cookie");
    private static final Set<String> DENIED_KEYS = Set.of(
            "authorization",
            "auth",
            "cookie",
            "setcookie",
            "proxyauthorization",
            "password",
            "passwd",
            "pwd",
            "secret",
            "clientsecret",
            "privatekey",
            "token",
            "accesstoken",
            "refreshtoken",
            "apikey");

    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)\\bbearer\\s+((?:\\\\.|[^\\s,;\"'])+)");
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "(?i)\\beyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:api[_-]?key|x-api-key)\\b\\s*[:=]\\s*|\"(?:api[_-]?key|x-api-key)\"\\s*:\\s*)"
                    + "(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'|([^\\s,;\"']+))"
                    + "|\\b(sk-[A-Za-z0-9_-]+)");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:password|passwd|pwd)\\b\\s*[=:]\\s*|\"(?:password|passwd|pwd)\"\\s*:\\s*)"
                    + "(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'|([^\\s&;,\"']+))");
    private static final Pattern URI_USERINFO_PATTERN = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9+.-]*://[^/\\s:@]*:((?:\\\\.|[^@/\\s\"'])+)@");
    private static final Pattern URI_QUERY_CREDENTIAL_PATTERN = Pattern.compile(
            "(?i)[?&](?:password|passwd|pwd|token|secret|api[_-]?key|apikey|access[_-]?token)=((?:\\\\.|[^&#\\s\"'])+)");

    private final ObjectMapper objectMapper;
    private final Set<String> deniedJsonPointers;
    private final int maxStringLength;
    private final int maxDepth;

    public DefaultEvidenceRedactor() {
        this(DEFAULT_DENIED_JSON_POINTERS, DEFAULT_MAX_STRING_LENGTH, DEFAULT_MAX_DEPTH);
    }

    public DefaultEvidenceRedactor(Set<String> deniedJsonPointers, int maxStringLength, int maxDepth) {
        this.objectMapper = new ObjectMapper();
        this.deniedJsonPointers = validatePointers(deniedJsonPointers);
        if (maxStringLength < 1 || maxStringLength > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("maxStringLength must be between 1 and " + MAX_STRING_LENGTH);
        }
        if (maxDepth < 1 || maxDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be between 1 and " + MAX_DEPTH);
        }
        this.maxStringLength = maxStringLength;
        this.maxDepth = maxDepth;
    }

    @Override
    public RedactionResult redact(JsonNode input) {
        Objects.requireNonNull(input, "input");
        var stats = new Stats();
        JsonNode redacted = visit(input, "", 0, null, stats);
        return new RedactionResult(redacted, stats.count, stats.rules, stats.truncated);
    }

    private JsonNode visit(JsonNode node, String pointer, int depth, String fieldName, Stats stats) {
        if (deniedJsonPointers.contains(pointer)) {
            return replacement("json-pointer", stats, false);
        }
        if (fieldName != null && isDeniedKey(fieldName)) {
            return replacement("key-denylist", stats, false);
        }
        if (depth > maxDepth) {
            return replacement("max-depth", stats, true);
        }
        if (node.isTextual()) {
            return redactText(node.asText(), stats);
        }
        if (node.isObject()) {
            return visitObject(node, pointer, depth, stats);
        }
        if (node.isArray()) {
            return visitArray(node, pointer, depth, stats);
        }
        return node.deepCopy();
    }

    private JsonNode redactText(String value, Stats stats) {
        boolean overlong = value.length() > maxStringLength;
        List<SecretMatch> matches = findSecretMatches(value);
        if (!matches.isEmpty()) {
            StringBuilder redacted = new StringBuilder(value.length());
            int cursor = 0;
            for (SecretMatch match : matches) {
                redacted.append(value, cursor, match.start());
                redacted.append("[REDACTED:credential]");
                cursor = match.end();
                stats.count++;
                stats.rules.add("credential");
            }
            redacted.append(value, cursor, value.length());
            if (overlong || redacted.length() > maxStringLength) {
                return replacement("string-length", stats, true);
            }
            return StringNode.valueOf(redacted.toString());
        }
        if (overlong) {
            return replacement("string-length", stats, true);
        }
        return StringNode.valueOf(value);
    }

    private List<SecretMatch> findSecretMatches(String value) {
        var candidates = new ArrayList<SecretMatch>();
        addMatches(candidates, BEARER_PATTERN, value, 1);
        addMatches(candidates, JWT_PATTERN, value, 0);
        addMatches(candidates, API_KEY_PATTERN, value, 1, 2, 3, 4);
        addMatches(candidates, PASSWORD_PATTERN, value, 1, 2, 3);
        addMatches(candidates, URI_USERINFO_PATTERN, value, 1);
        addMatches(candidates, URI_QUERY_CREDENTIAL_PATTERN, value, 1);
        candidates.sort(Comparator.comparingInt(SecretMatch::start).thenComparingInt(SecretMatch::priority));

        var accepted = new ArrayList<SecretMatch>(candidates.size());
        for (SecretMatch candidate : candidates) {
            if (accepted.isEmpty()) {
                accepted.add(candidate);
                continue;
            }
            int lastIndex = accepted.size() - 1;
            SecretMatch last = accepted.get(lastIndex);
            if (candidate.start() < last.end()) {
                if (candidate.end() > last.end()) {
                    accepted.set(lastIndex, new SecretMatch(last.start(), candidate.end(), last.priority()));
                }
            } else {
                accepted.add(candidate);
            }
        }
        return accepted;
    }

    private void addMatches(List<SecretMatch> matches, Pattern pattern, String value, int... groups) {
        var matcher = pattern.matcher(value);
        while (matcher.find()) {
            for (int group : groups) {
                if (group <= matcher.groupCount() && matcher.start(group) >= 0) {
                    matches.add(new SecretMatch(matcher.start(group), matcher.end(group), matches.size()));
                    break;
                }
            }
        }
    }

    private JsonNode visitObject(JsonNode node, String pointer, int depth, Stats stats) {
        ObjectNode result = objectMapper.createObjectNode();
        node.properties().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String childPointer = pointer + "/" + escapePointerToken(entry.getKey());
                    result.set(entry.getKey(), visit(entry.getValue(), childPointer, depth + 1,
                            entry.getKey(), stats));
                });
        return result;
    }

    private JsonNode visitArray(JsonNode node, String pointer, int depth, Stats stats) {
        ArrayNode result = objectMapper.createArrayNode();
        int index = 0;
        for (JsonNode element : node) {
            result.add(visit(element, pointer + "/" + index, depth + 1, null, stats));
            index++;
        }
        return result;
    }

    private boolean isDeniedKey(String key) {
        return DENIED_KEYS.contains(normalizeKey(key));
    }

    private static String normalizeKey(String key) {
        StringBuilder normalized = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char character = key.charAt(i);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }

    private JsonNode replacement(String rule, Stats stats, boolean truncated) {
        stats.count++;
        stats.rules.add(rule);
        stats.truncated |= truncated;
        return StringNode.valueOf("[REDACTED:" + rule + "]");
    }

    private static String escapePointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static Set<String> validatePointers(Set<String> pointers) {
        Objects.requireNonNull(pointers, "deniedJsonPointers");
        if (pointers.size() > MAX_DENIED_POINTERS) {
            throw new IllegalArgumentException("too many denied JSON pointers");
        }
        var validated = new TreeSet<String>();
        for (String pointer : pointers) {
            Objects.requireNonNull(pointer, "deniedJsonPointers contains null");
            if (pointer.length() > MAX_POINTER_LENGTH || (!pointer.isEmpty() && !pointer.startsWith("/"))) {
                throw new IllegalArgumentException("invalid JSON pointer");
            }
            validated.add(pointer);
        }
        return Collections.unmodifiableSet(validated);
    }

    private static final class Stats {
        private int count;
        private final Set<String> rules = new HashSet<>();
        private boolean truncated;
    }

    private record SecretMatch(int start, int end, int priority) {
    }
}
