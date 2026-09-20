package io.sentinelops.api.shared.idempotency;

import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.shared.time.TimeProvider;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final UuidV7Generator idGenerator;
    private final TimeProvider timeProvider;
    private final TransactionTemplate transactions;

    public IdempotencyService(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            UuidV7Generator idGenerator,
            TimeProvider timeProvider,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public Response execute(
            Scope scope, String key, String requestHash, Supplier<Response> action) {
        Objects.requireNonNull(scope, "scope");
        requireText(key, "idempotency key");
        requireText(requestHash, "request hash");
        Objects.requireNonNull(action, "action");

        int inserted = insertStarted(scope, key, requestHash);

        if (inserted == 0) {
            return replay(scope, key, requestHash);
        }

        var response = Objects.requireNonNull(action.get(), "action response");
        complete(scope, key, response);
        return response.copy();
    }

    public <T> Response executePostCommit(
            Scope scope,
            String key,
            String requestHash,
            Supplier<T> transactionalAction,
            Function<T, Response> postCommitAction) {
        return executePostCommit(
                scope,
                key,
                requestHash,
                transactionalAction,
                () -> {
                    throw conflict(
                            "IDEMPOTENCY_IN_PROGRESS",
                            "The earlier post-commit command has no recovery action.");
                },
                postCommitAction);
    }

    public <T> Response executePostCommit(
            Scope scope,
            String key,
            String requestHash,
            Supplier<T> transactionalAction,
            Supplier<T> recoveryAction,
            Function<T, Response> postCommitAction) {
        Objects.requireNonNull(scope, "scope");
        requireText(key, "idempotency key");
        requireText(requestHash, "request hash");
        Objects.requireNonNull(transactionalAction, "transactionalAction");
        Objects.requireNonNull(recoveryAction, "recoveryAction");
        Objects.requireNonNull(postCommitAction, "postCommitAction");

        var phase = Objects.requireNonNull(
                transactions.execute(status -> {
                    int inserted = insertStarted(scope, key, requestHash);
                    if (inserted == 0) {
                        var record = lockRecord(scope, key);
                        validateRequestHash(record, requestHash);
                        if ("completed".equals(record.state())) {
                            return PostCommitPhase.<T>replayed(toResponse(record));
                        }
                        return PostCommitPhase.work(Objects.requireNonNull(
                                recoveryAction.get(), "post-commit recovery result"));
                    }
                    return PostCommitPhase.work(Objects.requireNonNull(
                            transactionalAction.get(), "transactional action result"));
                }),
                "post-commit idempotency phase");
        if (phase.replay() != null) {
            return phase.replay().copy();
        }

        final Response response;
        try {
            response = Objects.requireNonNull(
                    postCommitAction.apply(phase.value()), "post-commit response");
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> markFailed(scope, key));
            throw failure;
        }
        var persisted = Objects.requireNonNull(
                transactions.execute(status -> completePostCommit(
                        scope, key, requestHash, response)),
                "post-commit idempotency response");
        return persisted.copy();
    }

    private int insertStarted(Scope scope, String key, String requestHash) {
        var now = timeProvider.now();
        return jdbc.sql("""
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
    }

    private void complete(Scope scope, String key, Response response) {
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
    }

    private void markFailed(Scope scope, String key) {
        jdbc.sql("""
                        update idempotency_record
                        set state = 'failed'
                        where principal_key = :principalKey
                          and route_key = :routeKey
                          and idempotency_key = :key
                          and state = 'started'
                        """)
                .param("principalKey", scope.principalKey())
                .param("routeKey", scope.routeKey())
                .param("key", key)
                .update();
    }

    private Response completePostCommit(
            Scope scope, String key, String requestHash, Response response) {
        String responseJson = objectMapper.writeValueAsString(response.body());
        int completed = jdbc.sql("""
                        update idempotency_record
                        set state = 'completed', response_status = :status,
                            response_body = cast(:body as jsonb)
                        where principal_key = :principalKey
                          and route_key = :routeKey
                          and idempotency_key = :key
                          and state in ('started','failed')
                        """)
                .param("status", response.status())
                .param("body", responseJson)
                .param("principalKey", scope.principalKey())
                .param("routeKey", scope.routeKey())
                .param("key", key)
                .update();
        if (completed == 1) {
            return response;
        }
        var record = lockRecord(scope, key);
        validateRequestHash(record, requestHash);
        if (!"completed".equals(record.state())) {
            throw new IllegalStateException("Idempotency record was not completed exactly once");
        }
        return toResponse(record);
    }

    private Response replay(Scope scope, String key, String requestHash) {
        var record = lockRecord(scope, key);
        validateRequestHash(record, requestHash);
        if ("failed".equals(record.state())) {
            throw conflict(
                    "IDEMPOTENCY_FAILED",
                    "The earlier command failed after its database transaction committed.");
        }
        if (!"completed".equals(record.state())) {
            throw conflict(
                    "IDEMPOTENCY_IN_PROGRESS",
                    "The command associated with this idempotency key is still in progress.");
        }
        return toResponse(record);
    }

    private StoredRecord lockRecord(Scope scope, String key) {
        return jdbc.sql("""
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
    }

    private void validateRequestHash(StoredRecord record, String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            throw conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used for a different request.");
        }
    }

    private Response toResponse(StoredRecord record) {
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

    private record PostCommitPhase<T>(T value, Response replay) {

        private static <T> PostCommitPhase<T> work(T value) {
            return new PostCommitPhase<>(value, null);
        }

        private static <T> PostCommitPhase<T> replayed(Response replay) {
            return new PostCommitPhase<>(null, replay);
        }
    }
}
