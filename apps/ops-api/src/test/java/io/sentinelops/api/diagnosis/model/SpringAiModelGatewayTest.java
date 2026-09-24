package io.sentinelops.api.diagnosis.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.sentinelops.api.diagnosis.adapter.out.model.*;
import io.sentinelops.api.diagnosis.application.model.*;
import io.sentinelops.api.diagnosis.application.tool.*;
import io.sentinelops.api.diagnosis.application.tool.ToolContext;
import io.sentinelops.api.diagnosis.domain.*;
import io.sentinelops.api.knowledge.application.*;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.shared.observability.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.ObjectMapper;

class SpringAiModelGatewayTest {
    private static final String VALID = """
            {"summary":"Manual inspection required","hypotheses":[],"missingEvidence":["health metrics"],
             "runbookVersionId":null,"parameters":{},"riskLevel":"R0","expectedVerification":null}
            """;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void rejectedToolCallCountsOnlyRegisteredNameAndFiniteResult() {
        var meters = new SimpleMeterRegistry();
        var metrics = new BusinessMetrics(ObservationRegistry.NOOP, meters,
                OpenTelemetry.noop());
        var request = request(Duration.ofSeconds(90));
        var config = new DiagnosisToolConfiguration(mock(EvidenceTools.class),
                mock(KnowledgeSearch.class), mock(EmbeddingGateway.class),
                mock(RunbookCatalog.class), mapper, metrics);
        var callback = config.callbacks(request,
                new DiagnosisToolConfiguration.BudgetState(request)).stream()
                .filter(tool -> tool.getToolDefinition().name().equals("getEvidence"))
                .findFirst().orElseThrow();

        assertThatThrownBy(() -> callback.call("prompt-canary-81bafa", null))
                .isInstanceOf(ApiProblemException.class);
        assertThat(meters.counter("sentinelops.diagnosis.tool.results",
                "tool", "getEvidence", "result", "rejected").count()).isEqualTo(1);
        assertThat(meters.getMeters().toString()).doesNotContain("prompt-canary-81bafa");
    }

    @Test void returnsStructuredProposalAndProviderMetadata() {
        var schema=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(mapper.readTree(new DiagnosisProposalOutputConverter().getJsonSchema()));
        assertThat(schema.validate(mapper.readTree(VALID))).isEmpty();
        var gateway = gateway(prompt -> response(VALID));
        var result = gateway.diagnose(request(Duration.ofSeconds(90)));
        assertThat(result.proposal().summary()).isEqualTo("Manual inspection required");
        assertThat(result.provider()).isEqualTo("openai-compatible");
        assertThat(result.responseHash()).matches("[a-f0-9]{64}");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void repairsOnlyOnceAndDoesNotSendInvalidResponseOrEvidenceAgain(org.springframework.boot.test.system.CapturedOutput output) {
        var calls = new AtomicInteger();
        var gateway = gateway(prompt -> {
            if (calls.getAndIncrement() == 0) return response("not-json secret-original-response");
            assertThat(prompt.getContents()).doesNotContain("secret-original-response", "injected-log-body");
            assertThat(prompt.getContents()).contains("MODEL_OUTPUT_INVALID", "responseHash");
            return response(VALID);
        });
        assertThat(gateway.diagnose(request(Duration.ofSeconds(90))).proposal()).isNotNull();
        assertThat(calls).hasValue(2);
        assertThat(output.getAll()).doesNotContain("secret-original-response", "injected-log-body");
    }

    @Test void secondInvalidResponseFailsWithTypedError() {
        var calls = new AtomicInteger();
        var gateway = gateway(prompt -> { calls.incrementAndGet(); return response("invalid secret"); });
        assertThatThrownBy(() -> gateway.diagnose(request(Duration.ofSeconds(90))))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.errorCode()).isEqualTo("MODEL_OUTPUT_INVALID"))
                .hasMessageNotContaining("secret");
        assertThat(calls).hasValue(2);
    }

    @Test void wallClockCancelsBlockedProviderWithoutWaitingForLateResult() {
        var gateway = gateway(prompt -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return response(VALID);
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway.diagnose(request(Duration.ofMillis(80))))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.errorCode()).isEqualTo("MODEL_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test void transportFailureIsSanitizedAndClassifiedSeparatelyFromInvalidOutput() {
        var gateway=gateway(prompt->{throw new IllegalStateException("secret provider response");});
        assertThatThrownBy(()->gateway.diagnose(request(Duration.ofSeconds(90))))
                .isInstanceOfSatisfying(ApiProblemException.class,e->assertThat(e.errorCode()).isEqualTo("AI_PROVIDER_FAILED"))
                .hasMessageNotContaining("secret provider response");
    }

    private SpringAiModelGateway gateway(java.util.function.Function<Prompt, ChatResponse> script) {
        var model = new ChatModel() { @Override public ChatResponse call(Prompt prompt) { return script.apply(prompt); } };
        return new SpringAiModelGateway(model, tools(), "openai-compatible", "test-model", ObservationRegistry.NOOP);
    }

    @Test void stopsAfterSixTotalToolCallsAcrossSchemaRepair() {
        var request=request(Duration.ofSeconds(90));
        var reads=new AtomicInteger();
        var rounds=new AtomicInteger();
        var evidence=mock(EvidenceTools.class);
        when(evidence.getEvidence(any(),any())).thenAnswer(call->{
            reads.incrementAndGet();
            return new ToolResult(UUID.randomUUID(),"log","logs",Instant.now(),"a".repeat(64),false,0,Set.of(),
                    TrustLevel.UNTRUSTED_EXTERNAL_DATA,mapper.createObjectNode().put("count",1));
        });
        var config=new DiagnosisToolConfiguration(evidence,mock(KnowledgeSearch.class),mock(EmbeddingGateway.class),mock(RunbookCatalog.class),mapper);
        var model=new ChatModel() {
            @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
            }
            @Override public ChatResponse call(Prompt prompt) {
            assertThat(prompt.getOptions()).isInstanceOf(org.springframework.ai.model.tool.ToolCallingChatOptions.class);
            int round=rounds.getAndIncrement();
            if(round==1) return response("invalid response");
            int count=round==0 ? 4 : 3;
            var calls=new ArrayList<AssistantMessage.ToolCall>();
            for(int i=0;i<count;i++) calls.add(new AssistantMessage.ToolCall("call-"+round+"-"+i,"function","getEvidence",
                    "{\"evidenceId\":\""+UUID.randomUUID()+"\"}"));
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
        }};
        try(var gateway=new SpringAiModelGateway(model,config,"openai-compatible","test-model",ObservationRegistry.NOOP)) {
            var failure=catchThrowable(()->gateway.diagnose(request));
            assertThat(reads).as("Reads after %s scripted model responses",rounds.get()).hasValue(6);
            assertThat(failure).isInstanceOfSatisfying(ApiProblemException.class,
                    problem->assertThat(problem.errorCode()).isEqualTo("TOOL_BUDGET_EXCEEDED"));
        }
    }

    private DiagnosisToolConfiguration tools() {
        return new DiagnosisToolConfiguration(mock(EvidenceTools.class), mock(KnowledgeSearch.class),
                mock(EmbeddingGateway.class), mock(RunbookCatalog.class), mapper);
    }

    private ModelDiagnosisRequest request(Duration duration) {
        UUID incident=UUID.randomUUID(), run=UUID.randomUUID(), service=UUID.randomUUID();
        var to=Instant.now();
        var context = new DiagnosisContext(incident,1,service,List.of(new DiagnosisEvidence(UUID.randomUUID(),
                "log","logs",mapper.createObjectNode().put("body","injected-log-body"),"hash",to,false)),
                run,to.minusSeconds(900),to,"corpus-v1");
        return new ModelDiagnosisRequest(context,new ToolContext(incident,run,service,to.minusSeconds(900),to),
                "diagnosis-system-v1",new ToolBudget(6,duration));
    }

    private static ChatResponse response(String value) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(value))));
    }
}
