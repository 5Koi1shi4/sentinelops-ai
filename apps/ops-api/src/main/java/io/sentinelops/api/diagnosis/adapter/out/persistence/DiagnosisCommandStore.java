package io.sentinelops.api.diagnosis.adapter.out.persistence;

import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.shared.idempotency.IdempotencyService.Response;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Called only inside the diagnosis short transactions, after locking the incident. */
@Repository
public class DiagnosisCommandStore {
    private static final String ROUTE = "POST:/api/v1/incidents/{id}/diagnosis-runs";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final UuidV7Generator ids;

    public DiagnosisCommandStore(JdbcClient jdbc, ObjectMapper mapper, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ids = ids;
    }

    public Command claim(String principal, String key, String hash) {
        jdbc.sql("""
                insert into idempotency_record(id,principal_key,route_key,idempotency_key,request_hash,
                  state,created_at,expires_at)
                values (:id,:principal,:route,:key,:hash,'started',clock_timestamp(),clock_timestamp()+interval '24 hours')
                on conflict (principal_key,route_key,idempotency_key) do nothing
                """).param("id", ids.generate()).param("principal", principal).param("route", ROUTE)
                .param("key", key).param("hash", hash).update();
        var command = jdbc.sql("""
                select id,request_hash,state,response_status,response_body::text
                from idempotency_record where principal_key=:principal and route_key=:route and idempotency_key=:key
                for update
                """).param("principal", principal).param("route", ROUTE).param("key", key)
                .query((row, n) -> new Command(row.getObject("id", UUID.class), row.getString("request_hash"),
                        "completed".equals(row.getString("state"))
                                ? new Response(row.getInt("response_status"), mapper.readTree(row.getString("response_body"))) : null))
                .single();
        if (!command.requestHash().equals(hash)) throw new ApiProblemException(HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED", "The idempotency key was already used for a different request.");
        return command;
    }

    public Response complete(UUID commandId, Response response) {
        int changed = jdbc.sql("""
                update idempotency_record set state='completed',response_status=:status,response_body=cast(:body as jsonb)
                where id=:id and state='started'
                """).param("id", commandId).param("status", response.status())
                .param("body", mapper.writeValueAsString(response.body())).update();
        if (changed == 1) return response;
        return jdbc.sql("select response_status,response_body::text from idempotency_record where id=:id and state='completed'")
                .param("id", commandId).query((row,n) -> new Response(row.getInt("response_status"),
                        mapper.readTree(row.getString("response_body")))).single();
    }

    public record Command(UUID id, String requestHash, Response response) {}
}
