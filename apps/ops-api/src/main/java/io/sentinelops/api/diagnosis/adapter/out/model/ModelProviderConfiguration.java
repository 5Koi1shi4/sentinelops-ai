package io.sentinelops.api.diagnosis.adapter.out.model;

import io.micrometer.observation.ObservationRegistry;
import io.sentinelops.api.diagnosis.application.*;
import io.sentinelops.api.diagnosis.application.model.ModelGateway;
import io.sentinelops.api.diagnosis.application.model.ModelGatewayFactory;
import io.sentinelops.api.diagnosis.application.tool.EvidenceTools;
import io.sentinelops.api.incident.application.evidence.*;
import io.sentinelops.api.knowledge.application.*;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.openai.*;
import org.springframework.ai.ollama.*;
import org.springframework.ai.ollama.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
public class ModelProviderConfiguration {
    @Bean ProviderSettings diagnosisProviderSettings(Environment environment) { return ProviderSettings.read(environment); }
    @Bean ToolCallingManager diagnosisToolCallingManager() {
        return SpringAiModelGateway.boundedToolCallingManager();
    }
    @Bean EvidenceTools diagnosisEvidenceTools(EvidenceCaptureService capture) {
        return new EvidenceTools(capture,new EvidenceBudget(200,128*1024,Duration.ofMinutes(15)));
    }
    @Bean DiagnosisToolConfiguration diagnosisTools(EvidenceTools evidence, KnowledgeSearch search,
            EmbeddingGateway embeddings,RunbookCatalog catalog,ObjectMapper mapper) {
        return new DiagnosisToolConfiguration(evidence,search,embeddings,catalog,mapper);
    }
    @Bean DiagnosisEngine diagnosisEngine(ModelGateway gateway) { return new ModelBackedDiagnosisEngine(gateway); }
    @Bean ModelGateway modelGateway(ProviderSettings settings, RunbookCatalog catalog, DiagnosisToolConfiguration tools,
                                    ToolCallingManager manager,ObservationRegistry observations) {
        return createGateway(settings, catalog, tools, manager, observations);
    }

    @Bean ModelGatewayFactory modelGatewayFactory(ProviderSettings settings, EmbeddingGateway embeddings,
            ToolCallingManager manager, ObservationRegistry observations, ObjectMapper mapper) {
        String prompt;
        try { prompt=new org.springframework.core.io.ClassPathResource("prompts/diagnosis-system-v1.st")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8); }
        catch(java.io.IOException failure) { throw new IllegalStateException("Diagnosis prompt unavailable"); }
        String promptHash=io.sentinelops.api.diagnosis.application.model.ModelPayloadHash.hash(prompt);
        String toolsetHash=io.sentinelops.api.diagnosis.application.model.ModelPayloadHash.hash(
                Map.of("version","read-tools-v1","schemas",DiagnosisToolConfiguration.schemas(),
                        "maxCalls",6,"maxSeconds",90,"maxInputBytes",8192,"maxOutputBytes",1048576));
        String configHash=io.sentinelops.api.diagnosis.application.model.ModelPayloadHash.hash(
                Map.of("provider",settings.provider(),"endpoint",Objects.toString(settings.baseUrl(),"local"),
                        "model",Objects.toString(settings.model(),"deterministic-v1"),"embedding",embeddings.modelId(),
                        "maxOutputTokens",4096,"retries",0));
        return new ModelGatewayFactory() {
            @Override public Descriptor descriptor() {
                return new Descriptor(settings.provider(), settings.model() == null ? settings.provider()+"-v1" : settings.model(),
                        "diagnosis-system-v1", "read-tools-v1", embeddings.modelId(),promptHash,toolsetHash,configHash);
            }
            @Override public Session open(EvidenceCapture evidence, RunbookLookup catalog, KnowledgeSearch search) {
                var callbacks = new DiagnosisToolConfiguration(new EvidenceTools(evidence,
                        new EvidenceBudget(200, 128*1024, Duration.ofMinutes(15))), search, embeddings, catalog, mapper);
                return new Session(createGateway(settings, catalog, callbacks, manager, observations));
            }
        };
    }

    private ModelGateway createGateway(ProviderSettings settings, RunbookLookup catalog, DiagnosisToolConfiguration tools,
            ToolCallingManager manager, ObservationRegistry observations) {
        return switch(settings.provider()) {
            case "deterministic" -> new DeterministicModelGateway(new DeterministicDiagnosisEngine(catalog));
            case "manual-only" -> new UnavailableModelGateway();
            case "openai-compatible" -> new SpringAiModelGateway(OpenAiChatModel.builder()
                    .options(OpenAiChatOptions.builder().model(settings.model()).baseUrl(settings.baseUrl())
                            .apiKey(settings.apiKey()).maxRetries(0).timeout(Duration.ofSeconds(90)).maxCompletionTokens(4096).build())
                    .observationRegistry(observations).build(),tools,settings.provider(),settings.model(),observations,manager);
            case "ollama" -> new SpringAiModelGateway(OllamaChatModel.builder().ollamaApi(ollamaApi(settings))
                    .retryTemplate(new org.springframework.core.retry.RetryTemplate(org.springframework.core.retry.RetryPolicy.withMaxRetries(0)))
                    .options(OllamaChatOptions.builder().model(settings.model()).numPredict(4096).build())
                    .observationRegistry(observations).build(),tools,settings.provider(),settings.model(),observations,manager);
            default -> throw new IllegalStateException("Unsupported provider");
        };
    }
    @Bean EmbeddingGateway embeddingGateway(ProviderSettings settings,ObservationRegistry observations) {
        if (settings.provider().equals("deterministic")) return new DeterministicEmbeddingGateway();
        if (settings.provider().equals("manual-only")) return texts->{ throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI_PROVIDER_UNAVAILABLE","Embedding provider is disabled."); };
        EmbeddingModel model=settings.provider().equals("openai-compatible")
                ? OpenAiEmbeddingModel.builder().options(OpenAiEmbeddingOptions.builder().model(settings.embeddingModel())
                    .baseUrl(settings.baseUrl()).apiKey(settings.apiKey()).dimensions(1536).maxRetries(0).timeout(Duration.ofSeconds(20)).build())
                    .observationRegistry(observations).build()
                : OllamaEmbeddingModel.builder().ollamaApi(ollamaApi(settings))
                    .options(OllamaEmbeddingOptions.builder().model(settings.embeddingModel()).build()).observationRegistry(observations).build();
        return new EmbeddingGateway() {
            @Override public String modelId() { return settings.embeddingModel(); }
            @Override public List<float[]> embed(List<String> texts) {
                if (texts==null || texts.isEmpty() || texts.size()>100) throw new IllegalArgumentException("Embedding batch must contain 1 to 100 texts");
                try {
                    var vectors=model.embed(texts);
                    if (vectors.size()!=texts.size()) throw new IllegalArgumentException("Embedding count mismatch");
                    vectors.forEach(KnowledgeQuery::validateEmbedding);
                    return vectors.stream().map(float[]::clone).toList();
                } catch (RuntimeException unsafe) {
                    throw new ApiProblemException(HttpStatus.BAD_GATEWAY,"EMBEDDING_UNAVAILABLE","Embedding request failed or violated the dimension contract.");
                }
            }
        };
    }
    private static OllamaApi ollamaApi(ProviderSettings settings) {
        var http=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        var factory=new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(90));
        return OllamaApi.builder().baseUrl(settings.baseUrl()).restClientBuilder(RestClient.builder().requestFactory(factory)).build();
    }

    /** Deliberately not a record: credentials must never appear in generated toString output. */
    static final class ProviderSettings {
        private final String provider,baseUrl,model,embeddingModel,apiKey;
        private ProviderSettings(String provider,String baseUrl,String model,String embeddingModel,String apiKey) {
            this.provider=provider;this.baseUrl=baseUrl;this.model=model;this.embeddingModel=embeddingModel;this.apiKey=apiKey;
        }
        String provider(){return provider;} String baseUrl(){return baseUrl;} String model(){return model;}
        String embeddingModel(){return embeddingModel;} String apiKey(){return apiKey;}
        static ProviderSettings read(Environment env) {
            String provider=env.getProperty("sentinelops.ai.provider","deterministic");
            if (!Set.of("deterministic","manual-only","openai-compatible","ollama").contains(provider)) throw new IllegalArgumentException("Unsupported AI provider");
            if (provider.equals("deterministic") && env.matchesProfiles("production")) throw new IllegalArgumentException("Production forbids deterministic AI");
            if (Set.of("deterministic","manual-only").contains(provider)) return new ProviderSettings(provider,null,null,null,null);
            String base=required(env,"base-url"), model=required(env,"model"), embedding=required(env,"embedding-model");
            URI uri=URI.create(base);
            if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("Invalid model endpoint");
            KnowledgeQuery.validateModel(model); KnowledgeQuery.validateModel(embedding);
            if (env.getProperty("sentinelops.ai.embedding-dimensions",Integer.class,0)!=1536) throw new IllegalArgumentException("Embedding dimensions must be explicitly 1536");
            String key=null;
            if (provider.equals("openai-compatible")) {
                String ref=required(env,"api-key-secret-ref");
                if (!ref.matches("env:[A-Z][A-Z0-9_]{0,127}")) throw new IllegalArgumentException("API key requires an env secret reference");
                key=env.getProperty(ref.substring(4));
                if (key==null || key.isBlank()) throw new IllegalArgumentException("AI secret reference is unresolved");
            }
            return new ProviderSettings(provider,base,model,embedding,key);
        }
        private static String required(Environment env,String key) {
            String value=env.getProperty("sentinelops.ai."+key);
            if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing AI setting: "+key);
            return value;
        }
    }
}
