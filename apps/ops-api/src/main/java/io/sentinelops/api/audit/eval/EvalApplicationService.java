package io.sentinelops.api.audit.eval;

import io.sentinelops.api.diagnosis.application.DiagnosisPolicy;
import io.sentinelops.api.diagnosis.application.ProposalHasher;
import io.sentinelops.api.diagnosis.application.model.*;
import io.sentinelops.api.diagnosis.application.tool.ToolContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import io.sentinelops.api.identity.application.*;
import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class EvalApplicationService {
    private final EvalStore store;
    private final EvalDatasetImporter importer;
    private final ModelGatewayFactory models;
    private final DiagnosisPolicy diagnosisPolicy;
    private final ProposalHasher proposalHasher;
    private final PrincipalLookup principals;
    private final UuidV7Generator ids;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final RuleBasedEvaluator evaluator = new RuleBasedEvaluator();
    private final EvalThresholdPolicy thresholds = new EvalThresholdPolicy();
    private final Semaphore capacity = new Semaphore(2);
    private final Long inputPrice, outputPrice;

    public EvalApplicationService(EvalStore store,EvalDatasetImporter importer,ModelGatewayFactory models,
            DiagnosisPolicy diagnosisPolicy,ProposalHasher proposalHasher,PrincipalLookup principals,
            UuidV7Generator ids,ObjectMapper mapper,PlatformTransactionManager transactions,Environment environment) {
        this.store=store;this.importer=importer;this.models=models;this.diagnosisPolicy=diagnosisPolicy;
        this.proposalHasher=proposalHasher;this.principals=principals;this.ids=ids;this.mapper=mapper;
        this.tx=new TransactionTemplate(transactions);this.tx.setTimeout(10);
        this.inputPrice=price(environment,"input-micros-per-million-tokens");
        this.outputPrice=price(environment,"output-micros-per-million-tokens");
    }

    public RunView run(RunRequest request,String key,CurrentPrincipal principal) {
        authorize(principal);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Eval requires a transaction-free caller");
        if(key==null || key.isBlank() || key.length()>200) throw new IllegalArgumentException("Invalid Idempotency-Key");
        var dataset=importer.builtin();
        if(!dataset.key().equals(request.datasetKey())) throw EvalStore.problem(HttpStatus.NOT_FOUND,"EVAL_DATASET_NOT_FOUND");
        var descriptor=models.descriptor();
        var acquired=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            var config=configuration(dataset,descriptor);
            var principalId=principals.upsert(principal,principal.subject());
            var claim=tx.execute(status->claim(request,key,principal,principalId,dataset,config,acquired));
            if(!claim.fresh()) return view(store.get(claim.id()));
            try {
                for(var item:store.cases(claim.dataset())) {
                    boolean live=Boolean.TRUE.equals(tx.execute(status->store.renew(claim.id(),claim.owner())));
                    if(!live) throw EvalStore.problem(HttpStatus.CONFLICT,"EVAL_LEASE_EXPIRED");
                    var result=evaluate(item,descriptor);
                    tx.executeWithoutResult(status->store.result(claim.id(),claim.dataset(),claim.owner(),result));
                }
                tx.executeWithoutResult(status->{
                    store.lockCommand(claim.command());
                    var results=store.results(claim.id());
                    var metrics=evaluator.aggregate(results.stream().map(ResultView::scores).toList());
                    var decision=thresholds.evaluate(metrics);
                    boolean complete=results.size()==dataset.cases().size();
                    boolean noErrors=results.stream().noneMatch(result->result.status().equals("error"));
                    var aggregate=mapper.valueToTree(metrics);
                    var object=(tools.jackson.databind.node.ObjectNode)aggregate;
                    object.put("caseCount",results.size()).put("expectedCaseCount",dataset.cases().size());
                    object.put("inputTokens",results.stream().mapToLong(ResultView::inputTokens).sum());
                    object.put("outputTokens",results.stream().mapToLong(ResultView::outputTokens).sum());
                    object.put("latencyMs",results.stream().mapToLong(ResultView::latencyMs).sum());
                    object.put("costMicros",results.stream().mapToLong(ResultView::costMicros).sum());
                    boolean usageKnown=results.stream().allMatch(result->result.failureCode()==null || "SERVICE_NOT_FOUND".equals(result.failureCode()));
                    object.put("costAvailable",descriptor.provider().equals("deterministic") || (inputPrice!=null && outputPrice!=null && noErrors && usageKnown));
                    object.set("thresholdFailures",mapper.valueToTree(decision.failures()));
                    boolean allowed=complete && noErrors && decision.releaseAllowed();
                    if(!store.complete(claim.id(),claim.owner(),aggregate,allowed)) throw EvalStore.problem(HttpStatus.CONFLICT,"EVAL_LEASE_EXPIRED");
                    store.completeCommand(claim.command(),claim.id());
                    store.audit(claim.id(),principal.principalKey(),"eval_completed",mapper.createObjectNode().put("releaseAllowed",allowed));
                });
            } catch(RuntimeException failure) {
                tx.executeWithoutResult(status->{
                    store.lockCommand(claim.command());
                    store.fail(claim.id(),claim.owner());
                    store.completeCommand(claim.command(),claim.id());
                    store.audit(claim.id(),principal.principalKey(),"eval_failed",mapper.createObjectNode().put("failureCode","EVAL_RUN_FAILED"));
                });
            }
            return view(store.get(claim.id()));
        } finally { if(acquired.get()) capacity.release(); }
    }

    public RunView get(UUID id,CurrentPrincipal principal) {
        authorize(principal);
        return tx.execute(status->{
            var row=store.get(id); store.lockCommand(row.commandId());
            if(store.expire(id)) store.completeCommand(row.commandId(),id);
            return view(store.get(id));
        });
    }

    public List<EvalRunSummary> list(UUID beforeId, int limit, CurrentPrincipal principal) {
        authorize(principal);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid Eval page size");
        if (beforeId != null && beforeId.version() != 7) throw new IllegalArgumentException("beforeId must be a UUIDv7 cursor");
        return store.summaries(beforeId, limit);
    }

    private Claim claim(RunRequest request,String key,CurrentPrincipal principal,UUID principalId,
            EvalDatasetImporter.Dataset dataset,JsonNode config,java.util.concurrent.atomic.AtomicBoolean acquired) {
        UUID command=store.claimCommand(principal.principalKey(),key,ModelPayloadHash.hash(request));
        var existing=store.commandRun(command);
        if(existing.isPresent()) {
            store.expire(existing.get());
            var row=store.get(existing.get());
            if(row.status().equals("running")) throw EvalStore.problem(HttpStatus.CONFLICT,"IDEMPOTENCY_IN_PROGRESS");
            store.completeCommand(command,row.id());
            return new Claim(row.id(),row.datasetId(),command,null,false);
        }
        if(config.path("provider").asString().equals("manual-only")) throw EvalStore.problem(HttpStatus.SERVICE_UNAVAILABLE,"AI_PROVIDER_UNAVAILABLE");
        if(!capacity.tryAcquire()) throw EvalStore.problem(HttpStatus.SERVICE_UNAVAILABLE,"EVAL_CAPACITY_EXCEEDED");
        acquired.set(true);
        UUID datasetId=store.dataset(dataset,principalId);
        if(request.baselineRunId()!=null) {
            var baseline=store.get(request.baselineRunId());
            if(!baseline.status().equals("completed") || !baseline.datasetId().equals(datasetId)
                    || !baseline.config().path("scoringVersion").equals(config.path("scoringVersion"))
                    || !baseline.config().path("policyVersion").equals(config.path("policyVersion"))) {
                throw EvalStore.problem(HttpStatus.CONFLICT,"EVAL_BASELINE_INCOMPATIBLE");
            }
        }
        UUID owner=ids.generate();
        UUID id=store.start(command,datasetId,request.baselineRunId(),owner,config,config.path("runbookCorpusHash").asString());
        store.audit(id,principal.principalKey(),"eval_started",mapper.createObjectNode().put("datasetChecksum",dataset.checksum()));
        return new Claim(id,datasetId,command,owner,true);
    }

    private ResultView evaluate(EvalStore.CaseRow row,ModelGatewayFactory.Descriptor descriptor) {
        var fixture=EvalFixture.parse(row.item().inputFixture(),mapper);
        var context=fixture.context();
        DiagnosisProposalDraft draft=null;
        ModelDiagnosisResult result=null;
        String failure=null,proposalHash=null;
        long started=System.nanoTime();
        try {
            if(!fixture.serviceKnown()) throw EvalStore.problem(HttpStatus.NOT_FOUND,"SERVICE_NOT_FOUND");
            try(var session=models.open(fixture,fixture,fixture)) {
                result=session.gateway().diagnose(new ModelDiagnosisRequest(context,
                        new ToolContext(context.incidentId(),context.runId(),context.serviceId(),context.evidenceFrom(),context.evidenceTo()),
                        descriptor.promptVersion(),ToolBudget.defaults()));
            }
            if(!descriptor.provider().equals(result.provider()) || !descriptor.modelName().equals(result.modelName())
                    || !descriptor.promptVersion().equals(result.promptVersion()) || !context.runbookCorpusVersion().equals(result.runbookCorpusVersion())) {
                throw EvalStore.problem(HttpStatus.BAD_GATEWAY,"MODEL_IDENTITY_MISMATCH");
            }
            draft=result.proposal();
            var validated=diagnosisPolicy.validate(draft,context,draft.runbookVersionId()==null ? null : fixture.findVersion(draft.runbookVersionId()).orElse(null));
            proposalHash=proposalHasher.hash(validated);
        } catch(ApiProblemException known) {
            failure=known.errorCode().matches("[A-Z][A-Z0-9_]{0,79}") ? known.errorCode() : "MODEL_REJECTED";
        } catch(RuntimeException unsafe) { failure="AI_PROVIDER_FAILED"; }
        var attemptedTools="TOOL_NOT_ALLOWED".equals(failure) ? List.of("unknown-tool") : List.<String>of();
        var scores=evaluator.score(draft,fixture.evidenceIds(),row.item().expectation(),failure,attemptedTools,fixture.runbookIds());
        boolean expectedFailure=failure!=null && mapper.convertValue(row.item().expectation().get("expectedFailureCodes"),List.class).contains(failure);
        boolean passed=scores.citationResolvable() && scores.runbookCorrect() && (!scores.safetyApplicable() || scores.dangerousActionBlocked())
                && (!scores.rootCauseApplicable() || scores.rootCauseTop3Correct()) && scores.fictionalToolCount()==0;
        String status=failure!=null && !expectedFailure ? "error" : passed ? "passed" : "failed";
        long input=result==null ? 0 : result.inputTokens(),output=result==null ? 0 : result.outputTokens();
        return new ResultView(row.id(),row.item().caseKey(),status,proposalHash,scores,failure,input,output,
                (System.nanoTime()-started)/1_000_000,cost(input,output));
    }

    private JsonNode configuration(EvalDatasetImporter.Dataset dataset,ModelGatewayFactory.Descriptor descriptor) {
        var config=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(descriptor);
        config.put("datasetChecksum",dataset.checksum()).put("fixtureHash",ModelPayloadHash.hash(dataset.cases().stream().map(EvalCase::inputFixture).toList()));
        config.put("runbookCorpusHash",ModelPayloadHash.hash(dataset.cases().stream().map(item->item.inputFixture().get("runbooks")).toList()));
        config.put("policyVersion","diagnosis-policy-v1").put("scoringVersion","eval-rules-v1");
        config.put("retrievalMode","fixed-fixture").put("maxToolCalls",6).put("caseTimeoutSeconds",90).put("currency","USD");
        config.set("inputMicrosPerMillionTokens",mapper.valueToTree(inputPrice));
        config.set("outputMicrosPerMillionTokens",mapper.valueToTree(outputPrice));
        return config;
    }
    private RunView view(EvalStore.RunRow row) {
        var results=store.results(row.id());
        Comparison comparison=null;
        if(row.baselineId()!=null) {
            var baseline=store.get(row.baselineId());
            var oldResults=store.results(baseline.id()).stream().collect(java.util.stream.Collectors.toMap(ResultView::caseKey,value->value));
            var changed=results.stream().filter(value->{var old=oldResults.get(value.caseKey());return old==null || !old.scores().equals(value.scores()) || !Objects.equals(old.proposalHash(),value.proposalHash());}).map(ResultView::caseKey).toList();
            var differences=new ArrayList<String>();
            row.config().properties().forEach(entry->{if(!Objects.equals(entry.getValue(),baseline.config().get(entry.getKey()))) differences.add(entry.getKey());});
            var deltas=mapper.createObjectNode();
            for(var metric:List.of("citationResolvableRate","dangerousActionBlockRate","runbookAccuracy","rootCauseTop3Accuracy","fictionalToolCount")) {
                if(row.metrics().path(metric).isNumber() && baseline.metrics().path(metric).isNumber()) deltas.put(metric,row.metrics().path(metric).decimalValue().subtract(baseline.metrics().path(metric).decimalValue()));
            }
            comparison=new Comparison(baseline.id(),deltas,changed,differences);
        }
        return new RunView(row.id(),row.status(),row.provider(),row.modelName(),row.datasetId(),row.datasetChecksum(),row.config(),
                row.metrics(),row.releaseAllowed(),row.startedAt(),row.completedAt(),results,comparison);
    }
    private void authorize(CurrentPrincipal principal) {
        if(!principal.hasAnyRole(PlatformRole.PLATFORM_ADMIN)) throw EvalStore.problem(HttpStatus.FORBIDDEN,"access_denied");
    }
    private static Long price(Environment env,String name) {
        Long value=env.getProperty("sentinelops.eval."+name,Long.class);
        if(value!=null && (value<0 || value>1_000_000_000_000L)) throw new IllegalArgumentException("Invalid Eval price");
        return value;
    }
    private long cost(long input,long output) {
        if(inputPrice==null || outputPrice==null) return 0;
        return BigInteger.valueOf(input).multiply(BigInteger.valueOf(inputPrice)).add(BigInteger.valueOf(output).multiply(BigInteger.valueOf(outputPrice)))
                .add(BigInteger.valueOf(999_999)).divide(BigInteger.valueOf(1_000_000)).longValueExact();
    }
    public record RunRequest(String datasetKey,UUID baselineRunId) {
        public RunRequest { if(datasetKey==null || !datasetKey.matches("[a-z][a-z0-9-]{0,79}")) throw new IllegalArgumentException("Invalid dataset key"); }
    }
    public record ResultView(UUID caseId,String caseKey,String status,String proposalHash,EvalScores scores,String failureCode,
                             long inputTokens,long outputTokens,long latencyMs,long costMicros) {}
    public record Comparison(UUID baselineRunId,JsonNode metricDeltas,List<String> changedCaseKeys,List<String> configurationDifferences) {}
    public record RunView(UUID id,String status,String provider,String modelName,UUID datasetId,String datasetChecksum,
                          JsonNode runConfig,JsonNode aggregateMetrics,Boolean releaseAllowed,Instant startedAt,Instant completedAt,
                          List<ResultView> results,Comparison comparison) {}
    public record EvalRunSummary(UUID id,String status,String provider,String modelName,UUID datasetId,
                                 Boolean releaseAllowed,Instant startedAt,Instant completedAt,JsonNode aggregateMetrics) {
        public EvalRunSummary { aggregateMetrics=aggregateMetrics.deepCopy(); }
        @Override public JsonNode aggregateMetrics() { return aggregateMetrics.deepCopy(); }
    }
    private record Claim(UUID id,UUID dataset,UUID command,UUID owner,boolean fresh) {}
}
