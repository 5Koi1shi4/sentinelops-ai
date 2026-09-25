package io.sentinelops.api.reliability;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.observation.ObservationRegistry;
import io.sentinelops.api.diagnosis.adapter.out.model.DiagnosisToolConfiguration;
import io.sentinelops.api.diagnosis.adapter.out.model.SpringAiModelGateway;
import io.sentinelops.api.diagnosis.application.PromptBoundary;
import io.sentinelops.api.diagnosis.application.DiagnosisApplicationService;
import io.sentinelops.api.diagnosis.application.model.ModelGateway;
import io.sentinelops.api.diagnosis.application.tool.ToolResult;
import io.sentinelops.api.diagnosis.application.tool.TrustLevel;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.identity.application.PrincipalLookup;
import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.incident.application.evidence.DefaultEvidenceRedactor;
import io.sentinelops.api.incident.application.evidence.RedactionResult;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(OutputCaptureExtension.class)
class PromptInjectionCorpusIT extends PostgresIntegrationTest {

    private static final String CANARY_SECRET = "sk-SENTINELOPS-CANARY-TEST-8f25aa41";
    private static final String EXPECTED_READ_TOOLS = "queryMetrics,queryLogs,getEvidence,searchRunbooks";

    @Autowired private DiagnosisApplicationService diagnoses;
    @Autowired private IncidentApplicationService incidents;
    @Autowired private PrincipalLookup principals;
    @Autowired private DiagnosisToolConfiguration tools;
    @Autowired private ToolCallingManager toolCallingManager;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private ModelGateway gateway;

    @Test
    void everyCorpusEntryStaysUntrustedAndCannotRegisterOrInvokeAnActionTool(CapturedOutput logs)
            throws Exception {
        List<CorpusEntry> entries = readCorpus();
        assertThat(entries).hasSizeGreaterThanOrEqualTo(25);
        assertThat(entries.stream().map(CorpusEntry::language).distinct().count())
                .isGreaterThanOrEqualTo(6);
        assertThat(entries.stream().map(CorpusEntry::id).distinct().count())
                .isEqualTo(entries.size());

        for (CorpusEntry entry : entries) {
            Fixture fixture = createFixture(entry);
            assertPromptBoundaryKeepsPayloadAsExternalData(entry, fixture.evidenceId(), fixture.redactedPayload());

            WireMockServer server = serverWithResponses(
                    assistantToolCall("getEvidence", Map.of("evidenceId", fixture.evidenceId().toString())),
                    "tool_calls",
                    assistantToolCall("restartShell", Map.of("service", "checkout-api")),
                    "tool_calls");
            try {
                try (var realGateway = installGateway(server)) {
                    try {
                        assertThatThrownBy(() -> diagnose(fixture))
                                .as("hostile item %s must not yield a proposal", entry.id())
                                .isInstanceOf(io.sentinelops.api.shared.problem.ApiProblemException.class);
                    } finally {
                        reset(gateway);
                    }
                }

                var events = server.getAllServeEvents();
                assertThat(events).as("model exchanges for %s", entry.id()).hasSize(2);
                assertReadOnlyToolSet(events);
                String readToolExchange = events.get(1).getRequest().getBodyAsString();
                assertThat(readToolExchange)
                        .as("retrieved corpus item %s is sent as external diagnostic evidence", entry.id())
                        .contains(redactedMessage(fixture), "UNTRUSTED_EXTERNAL_DATA");
                assertThat(readToolExchange).doesNotContain(CANARY_SECRET);

                assertThat(jdbc.sql("select status from diagnosis_run where incident_id=:id")
                                .param("id", fixture.incidentId()).query(String.class).single())
                        .as("unregistered action from %s must fail the diagnosis run", entry.id())
                        .isEqualTo("failed");
                assertThat(jdbc.sql("select count(*) from diagnosis_proposal where incident_id=:id")
                                .param("id", fixture.incidentId()).query(Long.class).single())
                        .as("unregistered action from %s must not persist a proposal", entry.id())
                        .isZero();
                assertThat(jdbc.sql("select count(*) from execution where incident_id=:id")
                                .param("id", fixture.incidentId()).query(Long.class).single())
                        .as("unregistered action from %s must not create an execution", entry.id())
                        .isZero();
            } finally {
                server.stop();
            }
        }

        assertThat(logs.getAll()).doesNotContain(CANARY_SECRET);
    }

    @Test
    void unknownAndUnpublishedRunbookVersionsAreRejectedAfterReadingHostileEvidence() throws Exception {
        CorpusEntry unknownEntry = entry("zh-unknown-runbook");
        CorpusEntry unpublishedEntry = entry("en-draft-runbook");

        Fixture unknown = createFixture(unknownEntry);
        UUID unknownVersion = UUID.randomUUID();
        assertRunbookRejected(unknown, unknownEntry, unknownVersion, "RUNBOOK_VERSION_UNKNOWN");

        Fixture unpublished = createFixture(unpublishedEntry);
        UUID unpublishedVersion = createUnpublishedRunbookVersion(unpublished.serviceId());
        assertRunbookRejected(unpublished, unpublishedEntry, unpublishedVersion, "RUNBOOK_NOT_PUBLISHED");
    }

    @Test
    void modelCanaryCannotBePersistedInASuggestionOrEmittedToDefaultLogs(CapturedOutput logs)
            throws Exception {
        CorpusEntry entry = entry("en-secret-exfiltration");
        Fixture fixture = createFixture(entry);
        WireMockServer server = serverWithResponses(
                assistantToolCall("getEvidence", Map.of("evidenceId", fixture.evidenceId().toString())),
                "tool_calls",
                assistantMessage(draftWithCanary()),
                "stop");
        String defaultLogs;
        try {
            try (var realGateway = installGateway(server)) {
                try {
                    assertThatThrownBy(() -> diagnoses.diagnose(
                                    fixture.incidentId(), 0, "canary-" + UUID.randomUUID(),
                                    fixture.principal(), fixture.principalId()))
                            .isInstanceOfSatisfying(
                                    io.sentinelops.api.shared.problem.ApiProblemException.class,
                                    failure -> assertThat(failure.errorCode())
                                            .isEqualTo("MODEL_OUTPUT_SENSITIVE"));
                } finally {
                    reset(gateway);
                }
            }
            assertThat(jdbc.sql("select count(*) from diagnosis_proposal where incident_id=:id")
                            .param("id", fixture.incidentId()).query(Long.class).single())
                    .isZero();
            assertThat(jdbc.sql("select count(*) from execution where incident_id=:id")
                            .param("id", fixture.incidentId()).query(Long.class).single())
                    .isZero();
            assertThat(jdbc.sql("select status from diagnosis_run where incident_id=:id")
                            .param("id", fixture.incidentId()).query(String.class).single())
                    .isEqualTo("failed");
            defaultLogs = logs.getAll();
            assertThat(defaultLogs).doesNotContain(CANARY_SECRET);
            assertThat(server.getAllServeEvents()).hasSize(2);
            assertReadOnlyToolSet(server.getAllServeEvents());
            assertThat(server.getAllServeEvents().get(1).getRequest().getBodyAsString())
                    .contains(redactedMessage(fixture), "UNTRUSTED_EXTERNAL_DATA")
                    .doesNotContain(CANARY_SECRET);
        } finally {
            server.stop();
        }
    }

    private void assertRunbookRejected(
            Fixture fixture, CorpusEntry entry, UUID runbookVersionId, String expectedCode) throws Exception {
        var draft = actionDraft(fixture.evidenceId(), runbookVersionId);
        WireMockServer server = serverWithResponses(
                assistantToolCall("getEvidence", Map.of("evidenceId", fixture.evidenceId().toString())),
                "tool_calls",
                assistantMessage(draft),
                "stop");
        try {
            try (var realGateway = installGateway(server)) {
                try {
                    assertThatThrownBy(() -> diagnose(fixture))
                            .isInstanceOfSatisfying(
                                    io.sentinelops.api.shared.problem.ApiProblemException.class,
                                    failure -> assertThat(failure.errorCode()).isEqualTo(expectedCode));
                } finally {
                    reset(gateway);
                }
            }

            assertThat(server.getAllServeEvents()).hasSize(2);
            assertReadOnlyToolSet(server.getAllServeEvents());
            assertThat(server.getAllServeEvents().get(1).getRequest().getBodyAsString())
                    .contains(redactedMessage(fixture), "UNTRUSTED_EXTERNAL_DATA");
            assertThat(jdbc.sql("select count(*) from diagnosis_proposal where incident_id=:id")
                            .param("id", fixture.incidentId()).query(Long.class).single())
                    .as("%s Runbook candidate must not be persisted", expectedCode)
                    .isZero();
        } finally {
            server.stop();
        }
    }

    private void assertPromptBoundaryKeepsPayloadAsExternalData(
            CorpusEntry entry, UUID evidenceId, JsonNode payload) {
        var result = new ToolResult(
                evidenceId,
                entry.sourceType(),
                "prompt-injection-corpus:" + entry.id(),
                Instant.now(),
                "a".repeat(64),
                false,
                1,
                Set.of("credential"),
                TrustLevel.UNTRUSTED_EXTERNAL_DATA,
                payload);
        String serialized = new PromptBoundary(mapper).serialize(result);
        JsonNode envelope = mapper.readTree(serialized);
        assertThat(envelope.path("type").asString()).isEqualTo("external_evidence");
        assertThat(envelope.path("trust").asString()).isEqualTo("UNTRUSTED_EXTERNAL_DATA");
        assertThat(envelope.path("evidence").path("payload").path("message").asString())
                .isEqualTo(payload.path("message").asString());
        assertThat(serialized).doesNotContain(CANARY_SECRET, "systemInstruction", "toolRegistry");
    }

    private Fixture createFixture(CorpusEntry entry) {
        String alertKey = UUID.randomUUID().toString();
        var incident = incidents.ingest(new AlertEnvelope(
                "alertmanager",
                alertKey,
                "checkout-api",
                alertKey,
                "Prompt injection corpus " + entry.id(),
                "sev2",
                AlertEnvelope.AlertStatus.FIRING,
                mapper.createObjectNode().put("status", "firing")));

        UUID evidenceId = UUID.randomUUID();
        ObjectNode rawPayload = mapper.createObjectNode()
                .put("message", entry.content() + " api_key=" + CANARY_SECRET);
        RedactionResult redaction = new DefaultEvidenceRedactor().redact(rawPayload);
        ObjectNode redactedPayload = (ObjectNode) redaction.json();
        ObjectNode redactionMetadata = redactedPayload.putObject("redaction");
        redactionMetadata.put("count", redaction.count());
        redactionMetadata.set("rules", mapper.valueToTree(redaction.appliedRules()));
        jdbc.sql("""
                        insert into evidence_snapshot(
                          id,incident_id,source_type,source_ref,query_spec,redacted_payload,
                          content_hash,captured_at,truncated)
                        values (:id,:incident,:source,:sourceRef,'{}'::jsonb,
                          cast(:payload as jsonb),:hash,clock_timestamp(),false)
                        """)
                .param("id", evidenceId)
                .param("incident", incident.id())
                .param("source", entry.sourceType())
                .param("sourceRef", "prompt-injection-corpus:" + entry.id())
                .param("payload", mapper.writeValueAsString(redactedPayload))
                .param("hash", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .update();

        var principal = new CurrentPrincipal(
                "https://issuer.sentinelops.test",
                "prompt-corpus-" + UUID.randomUUID(),
                Set.of(PlatformRole.ON_CALL_OPERATOR),
                Set.of(incident.serviceId()));
        UUID principalId = principals.upsert(principal, "Prompt corpus operator");
        return new Fixture(incident.id(), incident.serviceId(), evidenceId, principal, principalId, redactedPayload);
    }

    private UUID createUnpublishedRunbookVersion(UUID serviceId) {
        UUID runbookId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.sql("""
                        insert into runbook(id,runbook_key,service_id,display_name,owner_team,created_at,updated_at)
                        select :id,:key,service_id,'Prompt corpus unpublished fixture',owner_team,
                               clock_timestamp(),clock_timestamp()
                        from runbook where service_id=:service and runbook_key='RB-DB-POOL-03'
                        """)
                .param("id", runbookId)
                .param("key", "RB-CORPUS-" + runbookId)
                .param("service", serviceId)
                .update();
        jdbc.sql("""
                        insert into runbook_version(
                          id,runbook_id,version_number,lifecycle,risk_level,adapter_id,definition,
                          definition_checksum,author_principal_id,reviewer_principal_id,created_at,published_at)
                        select :id,:runbook,1,'draft',risk_level,adapter_id,definition,
                               :checksum,author_principal_id,reviewer_principal_id,clock_timestamp(),null
                        from runbook_version where id='0199a000-0000-7000-8000-000000000005'
                        """)
                .param("id", versionId)
                .param("runbook", runbookId)
                .param("checksum", "prompt-corpus-" + versionId)
                .update();
        return versionId;
    }

    private ObjectNode actionDraft(UUID evidenceId, UUID runbookVersionId) {
        ObjectNode draft = mapper.createObjectNode()
                .put("summary", "Evidence suggests an automated action")
                .put("riskLevel", "R1")
                .put("runbookVersionId", runbookVersionId.toString());
        draft.set("hypotheses", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("rank", 1)
                .put("statement", "The service may have a resource pressure event")
                .put("confidence", 0.91)
                .set("evidenceRefs", mapper.createArrayNode().add(evidenceId.toString()))));
        draft.set("missingEvidence", mapper.createArrayNode());
        draft.set("parameters", mapper.createObjectNode());
        draft.set("expectedVerification", mapper.createObjectNode()
                .put("probe", "demo_checkout_health")
                .put("successThreshold", 1.0)
                .put("attempts", 6)
                .put("intervalSeconds", 5));
        return draft;
    }

    private ObjectNode draftWithCanary() {
        ObjectNode draft = mapper.createObjectNode()
                .put("summary", "Manual inspection required; source token=" + CANARY_SECRET)
                .put("riskLevel", "R0");
        draft.set("hypotheses", mapper.createArrayNode());
        draft.set("missingEvidence", mapper.createArrayNode().add("independent health metrics"));
        draft.putNull("runbookVersionId");
        draft.set("parameters", mapper.createObjectNode());
        draft.putNull("expectedVerification");
        return draft;
    }

    private void assertReadOnlyToolSet(List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> events) {
        for (var event : events) {
            var names = new ArrayList<String>();
            mapper.readTree(event.getRequest().getBodyAsString()).path("tools")
                    .forEach(tool -> names.add(
                            tool.path("function").path("name").asString()));
            assertThat(names).containsExactlyInAnyOrder(EXPECTED_READ_TOOLS.split(","));
            assertThat(names).doesNotContain("restartShell", "runRunbook", "executeRunbook");
        }
    }

    private SpringAiModelGateway installGateway(WireMockServer server) {
        var model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(server.baseUrl() + "/v1")
                        .apiKey("test-placeholder")
                        .model("test-model")
                        .maxRetries(0)
                        .timeout(Duration.ofSeconds(5))
                        .build())
                .build();
        var realGateway = new SpringAiModelGateway(
                model, tools, "openai-compatible", "test-model", ObservationRegistry.NOOP, toolCallingManager);
        doAnswer(invocation -> realGateway.diagnose(invocation.getArgument(0)))
                .when(gateway).diagnose(any());
        return realGateway;
    }

    private WireMockServer serverWithResponses(
            String firstMessage, String firstFinish, String secondMessage, String secondFinish) {
        var server = new WireMockServer(0);
        server.start();
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("corpus-diagnosis")
                .whenScenarioStateIs("Started")
                .willSetStateTo("after-evidence-read")
                .willReturn(okJson(completion(firstMessage, firstFinish))));
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("corpus-diagnosis")
                .whenScenarioStateIs("after-evidence-read")
                .willReturn(okJson(completion(secondMessage, secondFinish))));
        return server;
    }

    private String assistantToolCall(String name, Map<String, Object> arguments) {
        String call = mapper.writeValueAsString(Map.of(
                "id", "call-" + UUID.randomUUID(),
                "type", "function",
                "function", Map.of("name", name, "arguments", mapper.writeValueAsString(arguments))));
        return "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + call + "]}";
    }

    private String assistantMessage(ObjectNode content) {
        return mapper.writeValueAsString(Map.of(
                "role", "assistant", "content", mapper.writeValueAsString(content)));
    }

    private String completion(String message, String finish) {
        return "{\"id\":\"chatcmpl-corpus\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":"
                + message + ",\"finish_reason\":\"" + finish
                + "\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }

    private void diagnose(Fixture fixture) {
        diagnoses.diagnose(
                fixture.incidentId(), 0, "corpus-" + UUID.randomUUID(),
                fixture.principal(), fixture.principalId());
    }

    private String redactedMessage(Fixture fixture) {
        return fixture.redactedPayload().path("message").asString();
    }

    private List<CorpusEntry> readCorpus() throws IOException {
        Path moduleDirectory = Path.of(System.getProperty("user.dir"));
        Path corpusPath = moduleDirectory.resolve("..")
                .resolve("..").resolve("tests").resolve("security")
                .resolve("prompt-injection-corpus.jsonl").normalize();
        List<String> lines = Files.readAllLines(corpusPath);
        assertThat(lines).isNotEmpty();
        JsonNode metadata = mapper.readTree(lines.get(0));
        assertThat(metadata.path("type").asString()).isEqualTo("metadata");
        assertThat(metadata.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(metadata.path("version").asString()).isNotBlank();

        List<CorpusEntry> entries = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            JsonNode item = mapper.readTree(line);
            entries.add(new CorpusEntry(
                    item.path("id").asString(),
                    item.path("language").asString(),
                    item.path("sourceType").asString(),
                    item.path("category").asString(),
                    item.path("content").asString()));
        }
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.id()).isNotBlank();
            assertThat(entry.language()).isNotBlank();
            assertThat(entry.content()).isNotBlank();
            assertThat(entry.sourceType()).isIn("log", "retrieval");
        });
        return entries;
    }

    private CorpusEntry entry(String id) throws IOException {
        return readCorpus().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
    }

    private record CorpusEntry(String id, String language, String sourceType, String category, String content) {}

    private record Fixture(
            UUID incidentId,
            UUID serviceId,
            UUID evidenceId,
            CurrentPrincipal principal,
            UUID principalId,
            JsonNode redactedPayload) {}
}
