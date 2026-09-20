package io.sentinelops.api.shared.idempotency;

import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.shared.time.TimeProvider;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final UuidV7Generator idGenerator;
    private final TimeProvider timeProvider;

    public IdempotencyService(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            UuidV7Generator idGenerator,
            TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public Response execute(
            Scope scope, String key, String requestHash, Supplier<Response> action) {
        Objects.requireNonNull(scope, "scope");
        requireText(key, "idempotency key");
        requireText(requestHash, "request hash");
        Objects.requireNonNull(action, "action");

        var now = timeProvider.now();
        int inserted = jdbc.sql("""
                        insert into idempotency_record(
                          id, principal_key, route_key, idempotency_key, request_hash,
                          state, created_at, expires_at
                        ) values (
                          :id, :principalKey, :routeKey, :key, :requestHash,
                          'started', :createdAt, :expiresAt
                        )
                        on conflict (principal_key, route_key, idempotency_key) do nothing
                        """)
                .param("id", idGenerator.generate())
                .param("principalKey", scope.principalKey())
                .param("routeKey", scope.routeKey())
                .param("key", key)
                .param("requestHash", requestHash)
                .param("createdAt", databaseTimestamp(now))
                .param("expiresAt", databaseTimestamp(now.plusSeconds(24 * 60 * 60)))
                .update();

        if (inserted == 0) {
            return replay(scope, key, requestHash);
        }

        var response = Objects.requireNonNull(action.get(), "action response");
        String responseJson = objectMapper.writeValueAsString(response.body());
        int completed = jdbc.sql("""
                        update idempotency_record
                        set state = 'completed', response_status = :status,
                            response_body = cast(:body as jsonb)
                        where principal_key = :principalKey
                          and route_key = :routeKey
                          and idempotency_key = :key
                          and state = 'started'
                        """)
                .param("status", response.status())
                .param("body", responseJson)
                .param("principalKey", scope.principalKey())
                .param("routeKey", scope.routeKey())
                .param("key", key)
                .update();
        if (completed != 1) {
            throw new IllegalStateException("Idempotency record was not completed exactly once");
        }
        return response.copy();
    }

    private Response replay(Scope scope, String key, String requestHash) {
        var record = jdbc.sql("""
                        select request_hash, response_status, response_body::text, state
                        from idempotency_record
                        where principal_key = :principalKey
                          and route_key = :routeKey
                          and idempotency_key = :key
                        for update
                        """)
                .param("principalKey", scope.principalKey())
                .param("routeKey", scope.routeKey())
                .param("key", key)
                .query((resultSet, rowNumber) -> new StoredRecord(
                        resultSet.getString("request_hash"),
                        resultSet.getObject("response_status", Integer.class),
                        resultSet.getString("response_body"),
                        resultSet.getString("state")))
                .single();

        if (!record.requestHash().equals(requestHash)) {
            throw conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used for a different request.");
        }
        if (!"completed".equals(record.state())) {
            throw conflict(
                    "IDEMPOTENCY_IN_PROGRESS",
                    "The command associated with this idempotency key is still in progress.");
        }
        return new Response(record.status(), objectMapper.readTree(record.bodyJson()));
    }

    private ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private OffsetDateTime databaseTimestamp(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record Scope(String principalKey, String routeKey) {
        public Scope {
            requireText(principalKey, "principal key");
            requireText(routeKey, "route key");
        }
    }

    public record Response(int status, JsonNode body) {
        public Response {
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("response status must be a valid HTTP status");
            }
            body = Objects.requireNonNull(body, "body").deepCopy();
        }

        private Response copy() {
            return new Response(status, body);
        }
    }

    private record StoredRecord(
            String requestHash, Integer status, String bodyJson, String state) {}
}
