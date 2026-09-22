package io.sentinelops.api.audit.eval;

import io.sentinelops.api.diagnosis.application.model.ModelPayloadHash;
import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
class EvalStore {
    private static final String ROUTE = "POST:/api/v1/eval-runs";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final UuidV7Generator ids;
    EvalStore(JdbcClient jdbc, ObjectMapper mapper, UuidV7Generator ids) { this.jdbc=jdbc; this.mapper=mapper; this.ids=ids; }

    UUID claimCommand(String principal, String key, String requestHash) {
        jdbc.sql("""
                insert into idempotency_record(id,principal_key,route_key,idempotency_key,request_hash,state,created_at,expires_at)
                values(:id,:principal,:route,:key,:hash,'started',clock_timestamp(),clock_timestamp()+interval '24 hours')
                on conflict(principal_key,route_key,idempotency_key) do nothing
                """).param("id",ids.generate()).param("principal",principal).param("route",ROUTE).param("key",key).param("hash",requestHash).update();
        var command=jdbc.sql("""
                select id,request_hash from idempotency_record
                where principal_key=:principal and route_key=:route and idempotency_key=:key for update
                """).param("principal",principal).param("route",ROUTE).param("key",key).query().singleRow();
        if (!requestHash.equals(command.get("request_hash"))) throw problem(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED");
        return (UUID)command.get("id");
    }
    Optional<UUID> commandRun(UUID command) {
        return jdbc.sql("select id from eval_run where command_id=:id").param("id",command).query(UUID.class).optional();
    }
    void lockCommand(UUID command) { jdbc.sql("select id from idempotency_record where id=:id for update").param("id",command).query(UUID.class).single(); }

    UUID dataset(EvalDatasetImporter.Dataset dataset, UUID principal) {
        UUID candidate=ids.generate();
        int inserted=jdbc.sql("""
                insert into eval_dataset(id,dataset_key,version_number,checksum,created_by_principal_id,created_at)
                values(:id,:key,:version,:hash,:principal,clock_timestamp()) on conflict(dataset_key,version_number) do nothing
                """).param("id",candidate).param("key",dataset.key()).param("version",dataset.version())
                .param("hash",dataset.checksum()).param("principal",principal).update();
        var row=jdbc.sql("select id,checksum from eval_dataset where dataset_key=:key and version_number=:version for update")
                .param("key",dataset.key()).param("version",dataset.version()).query().singleRow();
        if (!dataset.checksum().equals(row.get("checksum"))) throw problem(HttpStatus.CONFLICT,"EVAL_DATASET_VERSION_CONFLICT");
        UUID id=(UUID)row.get("id");
        if(inserted==1) for(var item:dataset.cases()) {
            jdbc.sql("""
                    insert into eval_case(id,dataset_id,case_key,input_fixture,expectation,tags,checksum)
                    values(:id,:dataset,:key,cast(:input as jsonb),cast(:expectation as jsonb),
                      array(select jsonb_array_elements_text(cast(:tags as jsonb))),:hash)
                    """).param("id",ids.generate()).param("dataset",id).param("key",item.caseKey())
                    .param("input",mapper.writeValueAsString(item.inputFixture())).param("expectation",mapper.writeValueAsString(item.expectation()))
                    .param("tags",mapper.writeValueAsString(item.tags())).param("hash",ModelPayloadHash.hash(item)).update();
        }
        return id;
    }

    List<CaseRow> cases(UUID dataset) {
        return jdbc.sql("select id,case_key,input_fixture::text,expectation::text,to_json(tags)::text tags from eval_case where dataset_id=:id order by case_key")
                .param("id",dataset).query((row,n)->new CaseRow(row.getObject("id",UUID.class),new EvalCase(row.getString("case_key"),
                        mapper.readTree(row.getString("input_fixture")),mapper.readTree(row.getString("expectation")),
                        mapper.readValue(row.getString("tags"),mapper.getTypeFactory().constructCollectionType(List.class,String.class))))).list();
    }

    UUID start(UUID command,UUID dataset,UUID baseline,UUID owner,JsonNode config,String corpus) {
        UUID id=ids.generate();
        jdbc.sql("""
                insert into eval_run(id,dataset_id,baseline_run_id,status,provider,model_name,prompt_version,toolset_version,
                  runbook_corpus_hash,run_config,started_at,command_id,owner_token,lease_expires_at,deadline_at)
                values(:id,:dataset,:baseline,'running',:provider,:model,:prompt,:tools,:corpus,cast(:config as jsonb),
                  statement_timestamp(),:command,:owner,statement_timestamp()+interval '120 seconds',statement_timestamp()+interval '20 minutes')
                """).param("id",id).param("dataset",dataset).param("baseline",baseline)
                .param("provider",config.path("provider").asString()).param("model",config.path("modelName").asString())
                .param("prompt",config.path("promptVersion").asString()).param("tools",config.path("toolsetVersion").asString())
                .param("corpus",corpus).param("config",mapper.writeValueAsString(config)).param("command",command).param("owner",owner).update();
        return id;
    }

    RunRow get(UUID id) {
        return jdbc.sql("""
                select r.*,d.checksum dataset_checksum,r.run_config::text config_json,r.aggregate_metrics::text metrics_json
                from eval_run r join eval_dataset d on d.id=r.dataset_id where r.id=:id
                """).param("id",id).query((row,n)->new RunRow(id,row.getObject("dataset_id",UUID.class),row.getObject("baseline_run_id",UUID.class),
                        row.getObject("command_id",UUID.class),row.getString("status"),row.getString("provider"),row.getString("model_name"),
                        row.getString("dataset_checksum"),mapper.readTree(row.getString("config_json")),mapper.readTree(row.getString("metrics_json")),
                        row.getObject("release_allowed",Boolean.class),row.getObject("started_at",OffsetDateTime.class).toInstant(),
                        row.getObject("completed_at",OffsetDateTime.class)==null ? null : row.getObject("completed_at",OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(()->problem(HttpStatus.NOT_FOUND,"EVAL_RUN_NOT_FOUND"));
    }

    boolean renew(UUID id,UUID owner) {
        return jdbc.sql("""
                update eval_run set lease_expires_at=least(deadline_at,clock_timestamp()+interval '120 seconds')
                where id=:id and owner_token=:owner and status='running'
                  and lease_expires_at>clock_timestamp() and deadline_at>clock_timestamp()
                """).param("id",id).param("owner",owner).update()==1;
    }

    void result(UUID run,UUID dataset,UUID owner,EvalApplicationService.ResultView result) {
        jdbc.sql("""
                insert into eval_case_result(id,eval_run_id,eval_case_id,dataset_id,owner_token,status,proposal_hash,scores,
                  failure_code,input_tokens,output_tokens,latency_ms,cost_micros,created_at)
                values(:id,:run,:case,:dataset,:owner,:status,:hash,cast(:scores as jsonb),:failure,:input,:output,:latency,:cost,clock_timestamp())
                """).param("id",ids.generate()).param("run",run).param("case",result.caseId()).param("dataset",dataset).param("owner",owner)
                .param("status",result.status()).param("hash",result.proposalHash()).param("scores",mapper.writeValueAsString(result.scores()))
                .param("failure",result.failureCode()).param("input",result.inputTokens()).param("output",result.outputTokens())
                .param("latency",result.latencyMs()).param("cost",result.costMicros()).update();
    }
    List<EvalApplicationService.ResultView> results(UUID id) {
        return jdbc.sql("""
                select r.*,c.case_key,r.scores::text scores_json from eval_case_result r join eval_case c on c.id=r.eval_case_id
                where r.eval_run_id=:id order by c.case_key
                """).param("id",id).query((row,n)->new EvalApplicationService.ResultView(row.getObject("eval_case_id",UUID.class),
                        row.getString("case_key"),row.getString("status"),row.getString("proposal_hash"),
                        mapper.readValue(row.getString("scores_json"),EvalScores.class),row.getString("failure_code"),
                        row.getLong("input_tokens"),row.getLong("output_tokens"),row.getLong("latency_ms"),row.getLong("cost_micros"))).list();
    }

    boolean complete(UUID id,UUID owner,JsonNode metrics,boolean allowed) {
        return jdbc.sql("""
                update eval_run set status='completed',aggregate_metrics=cast(:metrics as jsonb),release_allowed=:allowed,completed_at=clock_timestamp()
                where id=:id and owner_token=:owner and status='running' and lease_expires_at>clock_timestamp() and deadline_at>clock_timestamp()
                """).param("id",id).param("owner",owner).param("metrics",mapper.writeValueAsString(metrics)).param("allowed",allowed).update()==1;
    }
    boolean expire(UUID id) {
        return jdbc.sql("""
                update eval_run set status='failed',release_allowed=false,completed_at=clock_timestamp(),
                  aggregate_metrics='{"failureCode":"EVAL_INTERRUPTED"}'::jsonb
                where id=:id and status='running' and (lease_expires_at<=clock_timestamp() or deadline_at<=clock_timestamp())
                """).param("id",id).update()==1;
    }
    void fail(UUID id,UUID owner) {
        jdbc.sql("""
                update eval_run set status='failed',release_allowed=false,completed_at=clock_timestamp(),
                  aggregate_metrics='{"failureCode":"EVAL_RUN_FAILED"}'::jsonb
                where id=:id and status='running' and owner_token=:owner
                """).param("id",id).param("owner",owner).update();
    }
    void completeCommand(UUID command,UUID run) {
        jdbc.sql("""
                update idempotency_record set state='completed',response_status=201,response_body=cast(:body as jsonb)
                where id=:id and state='started'
                """).param("id",command).param("body",mapper.writeValueAsString(Map.of("id",run))).update();
    }
    void audit(UUID run,String actor,String action,JsonNode metadata) {
        jdbc.sql("""
                insert into audit_record(id,actor_type,actor_id,action,resource_type,resource_id,metadata,occurred_at)
                values(:id,'user',:actor,:action,'eval_run',:run,cast(:metadata as jsonb),clock_timestamp())
                """).param("id",ids.generate()).param("actor",actor).param("action",action).param("run",run.toString())
                .param("metadata",mapper.writeValueAsString(metadata)).update();
    }
    static ApiProblemException problem(HttpStatus status,String code) { return new ApiProblemException(status,code,"Eval request could not be completed."); }
    record CaseRow(UUID id,EvalCase item) {}
    record RunRow(UUID id,UUID datasetId,UUID baselineId,UUID commandId,String status,String provider,String modelName,
                  String datasetChecksum,JsonNode config,JsonNode metrics,Boolean releaseAllowed,Instant startedAt,Instant completedAt) {}
}
