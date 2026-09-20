package io.sentinelops.api.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class IdempotencyServiceIT extends PostgresIntegrationTest {

    @Autowired private IdempotencyService idempotency;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void clearRecords() {
        jdbc.sql("delete from idempotency_record").update();
    }

    @Test
    void executesOnceAndReplaysTheStoredResponseForTheSameRequest() {
        var invocations = new AtomicInteger();
        var scope = new IdempotencyService.Scope("demo-author", "POST:/diagnosis-runs");
        var responseBody = objectMapper.createObjectNode().put("proposalId", "P-12");

        var first = idempotency.execute(scope, "command-1", "request-A", () -> {
            invocations.incrementAndGet();
            return new IdempotencyService.Response(201, responseBody);
        });
        var replay = idempotency.execute(scope, "command-1", "request-A", () -> {
            invocations.incrementAndGet();
            return new IdempotencyService.Response(500, objectMapper.createObjectNode());
        });

        assertThat(first).isEqualTo(replay);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().path("proposalId").asString()).isEqualTo("P-12");
        assertThat(invocations).hasValue(1);
        assertThat(recordState("command-1")).isEqualTo("completed");
    }

    @Test
    void rejectsReuseOfAKeyForAChangedRequest() {
        var scope = new IdempotencyService.Scope("demo-author", "POST:/diagnosis-runs");
        idempotency.execute(
                scope,
                "command-2",
                "request-A",
                () -> new IdempotencyService.Response(201, objectMapper.createObjectNode()));

        assertThatThrownBy(() -> idempotency.execute(
                        scope,
                        "command-2",
                        "request-B",
                        () -> new IdempotencyService.Response(201, objectMapper.createObjectNode())))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status().value()).isEqualTo(409);
                    assertThat(problem.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
    }

    @Test
    void doesNotStealAnInProgressCommand() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        insert into idempotency_record(
                          id, principal_key, route_key, idempotency_key, request_hash,
                          state, created_at, expires_at
                        ) values (
                          :id, 'demo-author', 'POST:/diagnosis-runs', 'command-3',
                          'request-A', 'started', :now, :expiresAt
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("now", now)
                .param("expiresAt", now.plusHours(24))
                .update();

        assertThatThrownBy(() -> idempotency.execute(
                        new IdempotencyService.Scope(
                                "demo-author", "POST:/diagnosis-runs"),
                        "command-3",
                        "request-A",
                        () -> new IdempotencyService.Response(201, objectMapper.createObjectNode())))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status().value()).isEqualTo(409);
                    assertThat(problem.errorCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
                });
    }

    @Test
    void concurrentIdenticalCommandsExecuteTheActionOnce() throws Exception {
        var scope = new IdempotencyService.Scope("demo-author", "POST:/diagnosis-runs");
        var actionStarted = new CountDownLatch(1);
        var releaseAction = new CountDownLatch(1);
        var invocations = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> idempotency.execute(
                    scope,
                    "concurrent-same",
                    "request-A",
                    () -> heldResponse(actionStarted, releaseAction, invocations)));
            assertThat(actionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> idempotency.execute(
                    scope,
                    "concurrent-same",
                    "request-A",
                    () -> heldResponse(new CountDownLatch(0), new CountDownLatch(0), invocations)));
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseAction.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS))
                    .isEqualTo(second.get(5, TimeUnit.SECONDS));
            assertThat(invocations).hasValue(1);
            assertThat(recordCount("concurrent-same")).isOne();
            assertThat(recordState("concurrent-same")).isEqualTo("completed");
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentChangedRequestIsRejectedAfterTheWinnerCompletes() throws Exception {
        var scope = new IdempotencyService.Scope("demo-author", "POST:/diagnosis-runs");
        var actionStarted = new CountDownLatch(1);
        var releaseAction = new CountDownLatch(1);
        var invocations = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> idempotency.execute(
                    scope,
                    "concurrent-changed",
                    "request-A",
                    () -> heldResponse(actionStarted, releaseAction, invocations)));
            assertThat(actionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var contender = executor.submit(() -> idempotency.execute(
                    scope,
                    "concurrent-changed",
                    "request-B",
                    () -> heldResponse(new CountDownLatch(0), new CountDownLatch(0), invocations)));
            releaseAction.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThatThrownBy(() -> contender.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, execution -> {
                        assertThat(execution.getCause())
                                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                                        assertThat(problem.errorCode())
                                                .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
                    });
            assertThat(invocations).hasValue(1);
            assertThat(recordCount("concurrent-changed")).isOne();
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void actionFailureRollsBackTheStartedRecordAndAllowsRetry() {
        var scope = new IdempotencyService.Scope("demo-author", "POST:/diagnosis-runs");

        assertThatThrownBy(() -> idempotency.execute(
                        scope,
                        "failed-action",
                        "request-A",
                        () -> {
                            throw new IllegalStateException("forced action failure");
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced action failure");
        assertThat(recordCount("failed-action")).isZero();

        var retry = idempotency.execute(
                scope,
                "failed-action",
                "request-A",
                () -> new IdempotencyService.Response(201, objectMapper.createObjectNode()));
        assertThat(retry.status()).isEqualTo(201);
        assertThat(recordCount("failed-action")).isOne();
    }

    private IdempotencyService.Response heldResponse(
            CountDownLatch started, CountDownLatch release, AtomicInteger invocations) {
        invocations.incrementAndGet();
        started.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release idempotent action");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("idempotent action was interrupted", interrupted);
        }
        return new IdempotencyService.Response(
                201, objectMapper.createObjectNode().put("proposalId", "P-concurrent"));
    }

    private String recordState(String key) {
        return jdbc.sql("select state from idempotency_record where idempotency_key = :key")
                .param("key", key)
                .query(String.class)
                .single();
    }

    private long recordCount(String key) {
        return jdbc.sql("select count(*) from idempotency_record where idempotency_key = :key")
                .param("key", key)
                .query(Long.class)
                .single();
    }
}
