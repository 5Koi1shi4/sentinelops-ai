package io.sentinelops.api.incident.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.api.incident.application.evidence.DefaultEvidenceRedactor;
import io.sentinelops.api.incident.application.evidence.RedactionResult;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class EvidenceRedactorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void appliesPointersBeforeKeyDenylistAndLeavesInputUntouched() throws Exception {
        JsonNode input = mapper.readTree("""
                {"authorization":"Bearer source-token","nested":{"password":"source-password",
                 "api-key":"source-api-key","ordinary":"visible"},"metadata":{"query":"query-secret"}}
                """);

        RedactionResult result = new DefaultEvidenceRedactor(Set.of("/metadata/query"), 4096, 8).redact(input);

        assertThat(result.json().get("authorization").asText()).isEqualTo("[REDACTED:key-denylist]");
        assertThat(result.json().get("nested").get("password").asText()).isEqualTo("[REDACTED:key-denylist]");
        assertThat(result.json().get("nested").get("api-key").asText()).isEqualTo("[REDACTED:key-denylist]");
        assertThat(result.json().get("metadata").get("query").asText()).isEqualTo("[REDACTED:json-pointer]");
        assertThat(result.json().get("nested").get("ordinary").asText()).isEqualTo("visible");
        assertThat(result.count()).isEqualTo(4);
        assertThat(result.appliedRules()).containsExactlyInAnyOrder("json-pointer", "key-denylist");
        assertThat(input.get("authorization").asText()).isEqualTo("Bearer source-token");
        assertThat(input.get("nested").get("password").asText()).isEqualTo("source-password");
    }

    @ParameterizedTest
    @MethodSource("credentialStrings")
    void masksCredentialPatternsWithoutDiscardingSafeDiagnosticText(
            String value, String expected, int expectedCount) throws Exception {
        JsonNode input = mapper.readTree(mapper.writeValueAsString(java.util.Map.of("payload", value)));

        RedactionResult result = new DefaultEvidenceRedactor().redact(input);

        String redacted = result.json().get("payload").asText();
        assertThat(redacted.equals(expected)).as("credential replacement mismatch").isTrue();
        assertThat(result.count()).isEqualTo(expectedCount);
        assertThat(result.appliedRules()).containsExactly("credential");
        assertThat(result.json().toString()).doesNotContain(value);
    }

    static Stream<Arguments> credentialStrings() {
        return Stream.of(
                Arguments.of("Authorization: Bearer abc.def.ghi", "Authorization: Bearer [REDACTED:credential]", 1),
                Arguments.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature", "[REDACTED:credential]", 1),
                Arguments.of("api_key=sk-live-value", "api_key=[REDACTED:credential]", 1),
                Arguments.of("password=source-password state=ok", "password=[REDACTED:credential] state=ok", 1),
                Arguments.of("password=eyJabc.def.signature!tail state=ok",
                        "password=[REDACTED:credential] state=ok", 1),
                Arguments.of("https://user:pass@example.test/path?token=source-token",
                        "https://user:[REDACTED:credential]@example.test/path?token=[REDACTED:credential]", 2),
                Arguments.of("redis://:cache-password@cache.test/0",
                        "redis://:[REDACTED:credential]@cache.test/0", 1));
    }

    @Test
    void masksWholeOverlongStringsBeforeAnyPrefixCanLeak() throws Exception {
        String value = "safe-prefix-" + "x".repeat(64);
        JsonNode input = mapper.readTree(mapper.writeValueAsString(java.util.Map.of("payload", value)));

        RedactionResult result = new DefaultEvidenceRedactor(Set.of(), 8, 8).redact(input);

        assertThat(result.json().get("payload").asText()).isEqualTo("[REDACTED:string-length]");
        assertThat(result.json().toString()).doesNotContain("safe-prefix");
        assertThat(result.count()).isEqualTo(1);
        assertThat(result.appliedRules()).contains("string-length");
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void boundsDepthAndProducesStableDefensiveResults() throws Exception {
        JsonNode first = mapper.readTree("""
                {"z":{"b":{"deep":"value"},"a":[{"deeper":"value"}]},"a":"ok"}
                """);
        JsonNode second = mapper.readTree("""
                {"a":"ok","z":{"a":[{"deeper":"value"}],"b":{"deep":"value"}}}
                """);
        DefaultEvidenceRedactor redactor = new DefaultEvidenceRedactor(Set.of(), 64, 2);

        RedactionResult firstResult = redactor.redact(first);
        RedactionResult secondResult = redactor.redact(second);

        assertThat(firstResult.json().toString()).isEqualTo(secondResult.json().toString());
        assertThat(firstResult.json().get("z").get("b").get("deep").asText())
                .isEqualTo("[REDACTED:max-depth]");
        assertThat(firstResult.json().get("z").get("a").get(0).asText())
                .isEqualTo("[REDACTED:max-depth]");
        assertThat(firstResult.appliedRules()).contains("max-depth");

        JsonNode returned = firstResult.json();
        ((ObjectNode) returned).put("a", "changed");
        assertThat(firstResult.json().get("a").asText()).isEqualTo("ok");
        assertThatThrownBy(() -> firstResult.appliedRules().add("unexpected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void redactsWarningsLabelsAndQueryMetadataRecursively() throws Exception {
        JsonNode input = mapper.readTree("""
                {"warnings":[{"message":"Bearer warning-token"}],
                 "labels":{"api_key":"label-secret"},
                 "queryMetadata":{"url":"https://user:pass@example.test/?token=query-secret"}}
                """);

        RedactionResult result = new DefaultEvidenceRedactor().redact(input);

        assertThat(result.json().get("warnings").get(0).get("message").asText())
                .isEqualTo("Bearer [REDACTED:credential]");
        assertThat(result.json().get("labels").get("api_key").asText()).isEqualTo("[REDACTED:key-denylist]");
        assertThat(result.json().get("queryMetadata").get("url").asText())
                .contains("[REDACTED:credential]");
        assertThat(result.count()).isEqualTo(4);
    }

    @Test
    void redactsCredentialsInsideEmbeddedJsonWhileKeepingSafeFields() throws Exception {
        String value = "{\"password\":\"hunter2\",\"state\":\"ok\",\"api_key\":\"sk-live-secret\"}";
        JsonNode input = mapper.readTree(mapper.writeValueAsString(java.util.Map.of("message", value)));

        RedactionResult result = new DefaultEvidenceRedactor().redact(input);

        assertThat(result.json().get("message").asText())
                .isEqualTo("{\"password\":\"[REDACTED:credential]\",\"state\":\"ok\","
                        + "\"api_key\":\"[REDACTED:credential]\"}");
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.json().toString()).doesNotContain("hunter2", "sk-live-secret");
    }

    @Test
    void redactsEscapedCredentialQuotesInsideEmbeddedJsonWithoutLeakingSuffix() throws Exception {
        ObjectNode embedded = mapper.createObjectNode()
                .put("password", "first\"second")
                .put("state", "ok");
        String value = mapper.writeValueAsString(embedded);
        JsonNode input = mapper.createObjectNode().put("message", value);

        RedactionResult result = new DefaultEvidenceRedactor().redact(input);

        String redacted = result.json().get("message").asText();
        assertThat(redacted.contains("first") || redacted.contains("second"))
                .as("escaped credential was not fully redacted")
                .isFalse();
        assertThat(redacted).contains("\"state\":\"ok\"");
        assertThat(result.count()).isEqualTo(1);
    }

    @Test
    void redactsEscapedBearerAndUriQueryCredentialsInsideEmbeddedJson() throws Exception {
        ObjectNode embedded = mapper.createObjectNode()
                .put("authorization", "Authorization: Bearer first\"second")
                .put("url", "https://u/?token=first\"second")
                .put("state", "ok");
        String value = mapper.writeValueAsString(embedded);
        JsonNode input = mapper.createObjectNode().put("message", value);

        RedactionResult result = new DefaultEvidenceRedactor().redact(input);

        String redacted = result.json().get("message").asText();
        assertThat(redacted.contains("first") || redacted.contains("second"))
                .as("escaped bearer or URI credential was not fully redacted")
                .isFalse();
        assertThat(redacted).contains("\"state\":\"ok\"");
        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    void rejectsUnboundedConfiguration() {
        assertThatThrownBy(() -> new DefaultEvidenceRedactor(Set.of(), 0, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultEvidenceRedactor(Set.of(), 65_537, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultEvidenceRedactor(Set.of(), 4096, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultEvidenceRedactor(Set.of(), 4096, 33))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultEvidenceRedactor(Set.of("metadata/query"), 4096, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
