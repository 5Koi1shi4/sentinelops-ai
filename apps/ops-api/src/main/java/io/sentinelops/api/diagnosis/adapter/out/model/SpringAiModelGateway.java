package io.sentinelops.api.diagnosis.adapter.out.model;

import com.networknt.schema.*;
import io.micrometer.observation.*;
import io.sentinelops.api.diagnosis.application.model.*;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

public final class SpringAiModelGateway implements ModelGateway, AutoCloseable {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private final ChatModel model;
    private final DiagnosisToolConfiguration tools;
    private final String provider;
    private final String modelName;
    private final ObservationRegistry observations;
    private final ToolCallingManager toolCallingManager;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity=new Semaphore(8);
    private final String systemPrompt;

    public SpringAiModelGateway(ChatModel model, DiagnosisToolConfiguration tools, String provider,
                                String modelName, ObservationRegistry observations) {
        this(model, tools, provider, modelName, observations, boundedToolCallingManager());
    }

    public SpringAiModelGateway(ChatModel model, DiagnosisToolConfiguration tools, String provider,
                                String modelName, ObservationRegistry observations, ToolCallingManager toolCallingManager) {
        this.model=Objects.requireNonNull(model); this.tools=Objects.requireNonNull(tools);
        this.toolCallingManager=Objects.requireNonNull(toolCallingManager);
        this.provider=provider; this.modelName=modelName; this.observations=observations;
        if (!Set.of("openai-compatible","ollama").contains(provider) || !modelName.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) throw new IllegalArgumentException("Invalid model identity");
        try { systemPrompt=new ClassPathResource("prompts/diagnosis-system-v1.st").getContentAsString(StandardCharsets.UTF_8); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Diagnosis prompt unavailable",failure); }
    }

    @Override public ModelDiagnosisResult diagnose(ModelDiagnosisRequest request) {
        String input=MAPPER.writeValueAsString(Map.of("trust","UNTRUSTED_EXTERNAL_DATA","context",request.context()));
        if (input.getBytes(StandardCharsets.UTF_8).length>1_048_576) throw DiagnosisToolConfiguration.problem("MODEL_INPUT_LIMIT");
        if (!capacity.tryAcquire()) throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,"AI_PROVIDER_BUSY","Model capacity is occupied.");
        var budget=new DiagnosisToolConfiguration.BudgetState(request);
        long started=System.nanoTime();
        var observation=Observation.createNotStarted("sentinelops.diagnosis.model",observations)
                .lowCardinalityKeyValue("provider",provider).start();
        FutureTask<ModelDiagnosisResult> pending=new FutureTask<>(() -> call(request,input,budget,started));
        try { executor.execute(()->{ try { pending.run(); } finally { capacity.release(); } }); }
        catch (RejectedExecutionException closed) { capacity.release(); observation.stop(); throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,"AI_PROVIDER_UNAVAILABLE","Model provider is stopping."); }
        try {
          try {
            var result=pending.get(request.budget().maxDuration().toNanos(),TimeUnit.NANOSECONDS);
            observation.lowCardinalityKeyValue("result","success");
            return result;
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw new ApiProblemException(HttpStatus.GATEWAY_TIMEOUT,"MODEL_TIMEOUT","Diagnosis time budget expired.");
        } catch (InterruptedException failure) {
            pending.cancel(true); Thread.currentThread().interrupt();
            throw new ApiProblemException(HttpStatus.GATEWAY_TIMEOUT,"MODEL_TIMEOUT","Diagnosis was interrupted.");
        } catch (ExecutionException failure) {
            Throwable cause=failure.getCause();
            for (int depth=0; cause!=null && depth<8; depth++,cause=cause.getCause()) {
                if (cause instanceof ApiProblemException known) throw known;
            }
            throw new ApiProblemException(HttpStatus.BAD_GATEWAY,"AI_PROVIDER_FAILED","Diagnosis provider failed.");
          }
        } catch (ApiProblemException failure) {
            observation.lowCardinalityKeyValue("result","rejected");
            throw new ModelGatewayFailure(failure,provider,modelName,request,budget.count(),(System.nanoTime()-started)/1_000_000);
        } finally { budget.end(); observation.stop(); }
    }

    private ModelDiagnosisResult call(ModelDiagnosisRequest request, String input,
                                       DiagnosisToolConfiguration.BudgetState budget, long started) {
        var boundary=new RepairBoundary(budget);
        var converter=new DiagnosisProposalOutputConverter();
        var client=ChatClient.builder(model,ObservationRegistry.NOOP,null,null,
                        ToolCallingAdvisor.builder().advisorOrder(2).toolCallingManager(toolCallingManager))
                .defaultTemplateRenderer(new NoOpTemplateRenderer())
                .defaultSystem(systemPrompt)
                .defaultAdvisors(StructuredOutputValidationAdvisor.builder().advisorOrder(0)
                        .outputJsonSchema(converter.getJsonSchema())
                        .maxRepeatAttempts(1).build(),boundary,
                        new ModelResponseGuard(budget)).build();
        ResponseEntity<ChatResponse,DiagnosisProposalDraft> entity;
        try {
            entity=client.prompt().user(input).options(ToolCallingChatOptions.builder()
                    .toolCallbacks(tools.callbacks(request,budget))
                    .toolContext(Map.of(DiagnosisToolConfiguration.SCOPE_KEY,request.toolContext())))
                    .call().responseEntity(converter);
        } catch (RuntimeException failure) {
            Throwable cause=failure;
            for(int i=0;cause!=null && i<8;i++,cause=cause.getCause()) {
                if(cause instanceof ApiProblemException known) throw known;
                if(cause instanceof org.springframework.ai.model.tool.ToolCallLimitExceededException) throw DiagnosisToolConfiguration.problem("TOOL_BUDGET_EXCEEDED");
            }
            if(boundary.invalid && boundary.calls==2) throw DiagnosisToolConfiguration.problem("MODEL_OUTPUT_INVALID");
            throw failure;
        }
        budget.check();
        if (boundary.invalid || entity.entity()==null) throw DiagnosisToolConfiguration.problem("MODEL_OUTPUT_INVALID");
        var response=entity.response();
        var usage=response.getMetadata().getUsage();
        String finish=response.getResult().getMetadata().getFinishReason();
        if (finish==null || !Set.of("stop","length","tool_calls","end_turn").contains(finish)) finish="unknown";
        return new ModelDiagnosisResult(entity.entity(),provider,modelName,request.promptVersion(),request.context().runbookCorpusVersion(),
                ModelPayloadHash.hash(request),ModelPayloadHash.hash(entity.entity()),
                usage==null || usage.getPromptTokens()==null ? 0 : usage.getPromptTokens(),
                usage==null || usage.getCompletionTokens()==null ? 0 : usage.getCompletionTokens(),
                budget.count(),finish,(System.nanoTime()-started)/1_000_000);
    }

    private static final class ModelResponseGuard implements CallAdvisor {
        private final DiagnosisToolConfiguration.BudgetState budget;
        ModelResponseGuard(DiagnosisToolConfiguration.BudgetState budget){this.budget=budget;}
        @Override public String getName(){return "Diagnosis response boundary";}
        @Override public int getOrder(){return 3;}
        @Override public ChatClientResponse adviseCall(ChatClientRequest request,CallAdvisorChain chain){
            budget.check();
            var result=chain.nextCall(request);
            budget.check();
            if(result.chatResponse()==null || result.chatResponse().getResult()==null) throw DiagnosisToolConfiguration.problem("MODEL_OUTPUT_INVALID");
            for(var call:result.chatResponse().getResult().getOutput().getToolCalls()) {
                if(!Set.of("queryMetrics","queryLogs","getEvidence","searchRunbooks").contains(call.name())) throw DiagnosisToolConfiguration.problem("TOOL_NOT_ALLOWED");
                if(call.arguments()==null || call.arguments().getBytes(StandardCharsets.UTF_8).length>8192) throw DiagnosisToolConfiguration.problem("TOOL_INPUT_INVALID");
            }
            return result;
        }
    }

    /** The stock advisor only sees valid output or a fixed empty object, never an unsafe validation message. */
    private final class RepairBoundary implements CallAdvisor {
        private final DiagnosisToolConfiguration.BudgetState budget;
        private final Schema schema=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(MAPPER.readTree(new DiagnosisProposalOutputConverter().getJsonSchema()));
        private int calls;
        private String invalidHash;
        private boolean invalid;
        private RepairBoundary(DiagnosisToolConfiguration.BudgetState budget) { this.budget=budget; }
        @Override public String getName() { return "Sanitized diagnosis repair"; }
        @Override public int getOrder() { return 1; }
        @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            budget.check();
            if (++calls>2) throw DiagnosisToolConfiguration.problem("MODEL_OUTPUT_INVALID");
            if (calls==2) {
                String repair=MAPPER.writeValueAsString(Map.of("validationCodes",List.of("MODEL_OUTPUT_INVALID"),"responseHash",invalidHash));
                request=request.mutate().prompt(new Prompt(List.of(new SystemMessage(systemPrompt+"\n"+new DiagnosisProposalOutputConverter().getFormat()),new UserMessage(repair)),
                        request.prompt().getOptions())).build();
            }
            var result=chain.nextCall(request);
            budget.check();
            var response=result.chatResponse();
            if (response==null || response.getResult()==null || response.hasToolCalls()) throw DiagnosisToolConfiguration.problem("MODEL_OUTPUT_INVALID");
            if (response.hasFinishReasons(Set.of(org.springframework.ai.model.tool.ToolCallLimitExceededException.FINISH_REASON))) {
                throw DiagnosisToolConfiguration.problem("TOOL_BUDGET_EXCEEDED");
            }
            String raw=response.getResult().getOutput().getText();
            invalid=true;
            if (raw!=null && raw.getBytes(StandardCharsets.UTF_8).length<=65_536) {
                try {
                    var tree=MAPPER.readTree(raw);
                    invalid=!schema.validate(tree).isEmpty();
                    if (!invalid) MAPPER.treeToValue(tree,DiagnosisProposalDraft.class);
                } catch (RuntimeException unsafe) { invalid=true; }
            }
            if (!invalid) return result;
            invalidHash=ModelPayloadHash.hash(raw==null ? "" : raw.substring(0,Math.min(raw.length(),65_536)));
            return new ChatClientResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))),response.getMetadata()),result.context());
        }
    }
    static ToolCallingManager boundedToolCallingManager() {
        return ToolCallingManager.builder().maxCallsPerTool(6).maxTotalToolCalls(6)
                .resolutionFallbackEnabled(false).onLimitExceeded(ToolCallLimitBehavior.THROW)
                .toolExecutionExceptionProcessor(failure -> { throw failure; }).build();
    }

    @Override public void close() { executor.shutdownNow(); }
}
