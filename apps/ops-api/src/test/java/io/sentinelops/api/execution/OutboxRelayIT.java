package io.sentinelops.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sentinelops.api.execution.adapter.out.persistence.OutboxStore;
import io.sentinelops.api.execution.adapter.out.stream.ExecutionStreamPublisher;
import io.sentinelops.api.execution.adapter.out.stream.OutboxRelay;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

class OutboxRelayIT extends PostgresIntegrationTest {

    private static final String STREAM = "sentinelops.executions.test";

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(
                    DockerImageName.parse("valkey/valkey:8.1.10-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureValkey(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("sentinelops.outbox.stream", () -> STREAM);
        registry.add("sentinelops.outbox.relay-enabled", () -> false);
    }

    @Autowired private OutboxStore store;
    @Autowired private ExecutionStreamPublisher publisher;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void clearOutboxAndStream() {
        jdbc.sql("delete from outbox_event").update();
        redis.delete(STREAM);
    }

    @Test
    void relayClaimsDifferentRowsWithoutBlocking() throws Exception {
        for (int index = 0; index < 100; index++) {
            insertOutbox(UUID.randomUUID(), UUID.randomUUID());
        }
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return store.claimBatch("relay-a", 50);
            });
            var second = workers.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return store.claimBatch("relay-b", 50);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var firstRows = first.get(10, TimeUnit.SECONDS);
            var secondRows = second.get(10, TimeUnit.SECONDS);
            var allIds = new HashSet<UUID>();
            firstRows.forEach(row -> allIds.add(row.eventId()));
            secondRows.forEach(row -> allIds.add(row.eventId()));

            assertThat(firstRows).hasSize(50);
            assertThat(secondRows).hasSize(50);
            assertThat(allIds).hasSize(100);
        }
    }

    @Test
    void relayMarksOnlyPublishedRowsAndReleasesFailures() {
        UUID publishedEvent = UUID.randomUUID();
        UUID failedEvent = UUID.randomUUID();
        insertOutbox(publishedEvent, UUID.randomUUID());
        insertOutbox(failedEvent, UUID.randomUUID());
        var stream = mock(ExecutionStreamPublisher.class);
        doAnswer(invocation -> {
                    if (failedEvent.equals(invocation.getArgument(0, UUID.class))) {
                        throw new IllegalStateException("valkey unavailable with a very long detail");
                    }
                    return null;
                })
                .when(stream)
                .publish(any(UUID.class), any(UUID.class));
        var relay = new OutboxRelay(
                store,
                stream,
                new SimpleMeterRegistry(),
                "relay-test",
                50,
                256,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                20);

        assertThat(relay.relayOnce()).isEqualTo(1);

        var published = outboxState(publishedEvent);
        var failed = outboxState(failedEvent);
        assertThat(published.published()).isTrue();
        assertThat(published.claimedBy()).isNull();
        assertThat(failed.published()).isFalse();
        assertThat(failed.claimedBy()).isNull();
        assertThat(failed.lastError()).contains("IllegalStateException");
        assertThat(failed.lastError().length()).isLessThanOrEqualTo(256);
        assertThat(failed.nextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(failed.quarantined()).isFalse();
    }

    @Test
    void publisherWritesOnlyMinimalIdentifiersToTheExecutionStream() {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        publisher.publish(eventId, executionId);

        var records = redis.opsForStream().range(STREAM, Range.unbounded());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getValue())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "eventId", eventId.toString(),
                        "executionId", executionId.toString()));
    }

    @Test
    void failedRowsDoNotStarveNewOutboxEvents() {
        var eventIds = new ArrayList<UUID>();
        Instant createdAt = Instant.parse("2026-09-20T10:00:00Z");
        for (int index = 0; index < 2; index++) {
            UUID eventId = UUID.randomUUID();
            eventIds.add(eventId);
            insertOutbox(eventId, UUID.randomUUID(), createdAt.plusMillis(index));
        }
        var unavailableStream = mock(ExecutionStreamPublisher.class);
        doAnswer(invocation -> {
                    throw new IllegalStateException("valkey unavailable");
                })
                .when(unavailableStream)
                .publish(any(UUID.class), any(UUID.class));
        var relay = new OutboxRelay(
                store,
                unavailableStream,
                new SimpleMeterRegistry(),
                "relay-backoff",
                1,
                256,
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                20);

        assertThat(relay.relayOnce()).isZero();

        assertThat(store.claimBatch("next-relay", 1))
                .extracting(OutboxStore.ClaimedOutboxRecord::eventId)
                .containsExactly(eventIds.getLast());
    }

    @Test
    void exhaustedPublishAttemptsAreQuarantinedAndMeasured() {
        UUID eventId = UUID.randomUUID();
        insertOutbox(eventId, UUID.randomUUID());
        var unavailableStream = mock(ExecutionStreamPublisher.class);
        doAnswer(invocation -> {
                    throw new IllegalStateException("invalid stream record");
                })
                .when(unavailableStream)
                .publish(any(UUID.class), any(UUID.class));
        var meters = new SimpleMeterRegistry();
        var relay = new OutboxRelay(
                store,
                unavailableStream,
                meters,
                "relay-quarantine",
                1,
                256,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                1);

        assertThat(relay.relayOnce()).isZero();

        var state = outboxState(eventId);
        assertThat(state.quarantined()).isTrue();
        assertThat(state.nextAttemptAt()).isNull();
        assertThat(store.claimBatch("other-relay", 1)).isEmpty();
        assertThat(meters.counter("sentinelops.outbox.quarantined").count()).isEqualTo(1);
        assertThat(meters.get("sentinelops.outbox.quarantine.backlog").gauge().value())
                .isEqualTo(1);
    }

    private void insertOutbox(UUID eventId, UUID executionId) {
        insertOutbox(eventId, executionId, Instant.now());
    }

    private void insertOutbox(UUID eventId, UUID executionId, Instant createdAt) {
        store.insert(
                eventId,
                executionId,
                "execution.requested.v1",
                "{\"eventId\":\"" + eventId + "\",\"executionId\":\""
                        + executionId + "\"}",
                createdAt);
    }

    private OutboxState outboxState(UUID eventId) {
        return jdbc.sql("""
                        select published_at is not null as published,
                               claimed_by, last_error, next_attempt_at,
                               quarantined_at is not null as quarantined
                        from outbox_event
                        where id = :id
                        """)
                .param("id", eventId)
                .query((resultSet, rowNumber) -> {
                    var nextAttempt = resultSet.getObject(
                            "next_attempt_at", java.time.OffsetDateTime.class);
                    return new OutboxState(
                            resultSet.getBoolean("published"),
                            resultSet.getString("claimed_by"),
                            resultSet.getString("last_error"),
                            nextAttempt == null ? null : nextAttempt.toInstant(),
                            resultSet.getBoolean("quarantined"));
                })
                .single();
    }

    private record OutboxState(
            boolean published,
            String claimedBy,
            String lastError,
            Instant nextAttemptAt,
            boolean quarantined) {}
}
