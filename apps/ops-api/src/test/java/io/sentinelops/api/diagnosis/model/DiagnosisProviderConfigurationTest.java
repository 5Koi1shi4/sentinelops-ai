package io.sentinelops.api.diagnosis.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import io.micrometer.observation.ObservationRegistry;
import io.sentinelops.api.diagnosis.adapter.out.model.ModelProviderConfiguration;
import io.sentinelops.api.diagnosis.application.DiagnosisEngine;
import io.sentinelops.api.diagnosis.application.model.ModelGateway;
import io.sentinelops.api.incident.application.evidence.EvidenceCaptureService;
import io.sentinelops.api.shared.observability.BusinessMetrics;
import io.sentinelops.api.knowledge.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class DiagnosisProviderConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(ModelProviderConfiguration.class)
            .withBean(RunbookCatalog.class,()->mock(RunbookCatalog.class))
            .withBean(KnowledgeSearch.class,()->mock(KnowledgeSearch.class))
            .withBean(EvidenceCaptureService.class,()->mock(EvidenceCaptureService.class))
            .withBean(ObjectMapper.class,ObjectMapper::new)
            .withBean(BusinessMetrics.class,()->mock(BusinessMetrics.class))
            .withBean(ObservationRegistry.class,()->ObservationRegistry.NOOP);

    @ParameterizedTest @ValueSource(strings={"deterministic","manual-only","openai-compatible","ollama"})
    void exactlyOneEngineGatewayAndEmbeddingPortPerSupportedProvider(String provider) {
        runner.withPropertyValues("sentinelops.ai.provider="+provider,"sentinelops.ai.base-url=http://127.0.0.1:9",
                "sentinelops.ai.model=test-model","sentinelops.ai.embedding-model=test-embedding",
                "sentinelops.ai.embedding-dimensions=1536","sentinelops.ai.api-key-secret-ref=env:MODEL_TEST_KEY",
                "MODEL_TEST_KEY=test-placeholder")
                .run(context->{ assertThat(context).hasNotFailed(); assertThat(context).hasSingleBean(DiagnosisEngine.class)
                        .hasSingleBean(ModelGateway.class).hasSingleBean(EmbeddingGateway.class); });
    }
    @Test void productionRejectsDeterministicInsteadOfPretendingToUseAI() {
        runner.withPropertyValues("spring.profiles.active=production","sentinelops.ai.provider=deterministic")
                .run(context->assertThat(context).hasFailed());
    }
    @Test void demoUsesDeterministicEmbeddingAndCannotOverrideProductionRestriction() {
        runner.withPropertyValues("spring.profiles.active=demo").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmbeddingGateway.class)).isInstanceOf(DeterministicEmbeddingGateway.class);
        });
        runner.withPropertyValues("spring.profiles.active=production,demo")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void productionAllowsExplicitManualMode() {
        runner.withPropertyValues("spring.profiles.active=production","sentinelops.ai.provider=manual-only")
                .run(context->assertThat(context).hasNotFailed());
    }
    @Test void realProviderRequiresExplicitModelsAndSecretReference() {
        runner.withPropertyValues("sentinelops.ai.provider=openai-compatible")
                .run(context->assertThat(context).hasFailed());
    }
    @Test void rejectsEmbeddingDimensionMismatch() {
        runner.withPropertyValues("sentinelops.ai.provider=ollama","sentinelops.ai.base-url=http://127.0.0.1:9",
                "sentinelops.ai.model=test","sentinelops.ai.embedding-model=test","sentinelops.ai.embedding-dimensions=768")
                .run(context->assertThat(context).hasFailed());
    }

    @ParameterizedTest @ValueSource(strings={"openai-compatible","ollama"})
    void configuredProvidersUseTheirChatAndEmbeddingHttpProtocols(String provider) {
        var server = new com.github.tomakehurst.wiremock.WireMockServer(0);
        var mapper = new ObjectMapper();
        server.start();
        try {
            String draft = """
                    {"summary":"Manual inspection required","hypotheses":[],"missingEvidence":["metrics"],
                    "runbookVersionId":null,"parameters":{},"riskLevel":"R0","expectedVerification":null}
                    """;
            boolean openai = provider.equals("openai-compatible");
            String chatPath = openai ? "/v1/chat/completions" : "/api/chat";
            String embedPath = openai ? "/v1/embeddings" : "/api/embed";
            var message = java.util.Map.of("role", "assistant", "content", draft);
            Object chat = openai
                    ? java.util.Map.of("id", "test", "object", "chat.completion", "created", 1, "model", "test-model",
                        "choices", java.util.List.of(java.util.Map.of("index", 0, "message", message, "finish_reason", "stop")))
                    : java.util.Map.of("model", "test-model", "created_at", "2026-09-22T00:00:00Z", "message", message,
                        "done", true, "done_reason", "stop", "prompt_eval_count", 10, "eval_count", 5);
            var vector = java.util.Collections.nCopies(1536, 0.1);
            Object embedding = openai
                    ? java.util.Map.of("object", "list", "model", "test-embedding", "data", java.util.List.of(
                        java.util.Map.of("object", "embedding", "index", 0, "embedding", vector)),
                        "usage", java.util.Map.of("prompt_tokens", 1, "total_tokens", 1))
                    : java.util.Map.of("model", "test-embedding", "embeddings", java.util.List.of(vector));
            server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(chatPath)
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson(mapper.writeValueAsString(chat))));
            server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(embedPath)
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson(mapper.writeValueAsString(embedding))));
            runner.withPropertyValues("sentinelops.ai.provider="+provider,
                    "sentinelops.ai.base-url="+server.baseUrl()+(openai ? "/v1" : ""),
                    "sentinelops.ai.model=test-model", "sentinelops.ai.embedding-model=test-embedding",
                    "sentinelops.ai.embedding-dimensions=1536", "sentinelops.ai.api-key-secret-ref=env:MODEL_TEST_KEY",
                    "MODEL_TEST_KEY=test-placeholder").run(context -> {
                assertThat(context).hasNotFailed();
                var now = java.time.Instant.now();
                var incident = java.util.UUID.randomUUID();
                var run = java.util.UUID.randomUUID();
                var service = java.util.UUID.randomUUID();
                var frozen = new io.sentinelops.api.diagnosis.domain.DiagnosisContext(incident, 1, service,
                        java.util.List.of(), run, now.minusSeconds(900), now, "corpus-v1");
                var result = context.getBean(DiagnosisEngine.class).diagnoseWithMetadata(frozen);
                assertThat(result.provider()).isEqualTo(provider);
                assertThat(result.proposal().summary()).isEqualTo("Manual inspection required");
                assertThat(context.getBean(EmbeddingGateway.class).embed(java.util.List.of("test query")))
                        .singleElement().satisfies(values -> assertThat(values).hasSize(1536));
            });
            assertThat(server.getAllServeEvents()).hasSize(2);
        } finally {
            server.stop();
        }
    }
}
