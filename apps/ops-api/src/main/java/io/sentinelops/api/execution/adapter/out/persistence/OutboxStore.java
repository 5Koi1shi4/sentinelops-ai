package io.sentinelops.api.execution.adapter.out.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxStore {

    private final JdbcClient jdbc;

    public OutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID eventId,
            UUID aggregateId,
            String eventType,
            String payloadJson,
            Instant createdAt) {
        jdbc.sql("""
                        insert into outbox_event(
                          id, aggregate_type, aggregate_id, event_type, payload, created_at
                        ) values (
                          :id, 'execution', :aggregateId, :eventType,
                          cast(:payload as jsonb), :createdAt
                        )
                        """)
                .param("id", eventId)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .param("createdAt", timestamp(createdAt))
                .update();
    }

    public List<OutboxRecord> unpublishedForAggregate(UUID aggregateId) {
        return jdbc.sql("""
                        select id, event_type, payload::text, created_at
                        from outbox_event
                        where aggregate_type = 'execution'
                          and aggregate_id = :aggregateId
                          and published_at is null
                        order by created_at, id
                        """)
                .param("aggregateId", aggregateId)
                .query((resultSet, rowNumber) -> new OutboxRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Transactional
    public List<ClaimedOutboxRecord> claimBatch(String relayId, int batchSize) {
        if (relayId == null || relayId.isBlank()) {
            throw new IllegalArgumentException("relayId must not be blank");
        }
        if (batchSize < 1 || batchSize > 50) {
            throw new IllegalArgumentException("batchSize must be between 1 and 50");
        }
        return jdbc.sql("""
                        with candidates as materialized (
                          select id
                          from outbox_event
                          where aggregate_type = 'execution'
                            and event_type = 'execution.requested.v1'
                            and published_at is null
                            and quarantined_at is null
                            and (next_attempt_at is null
                                 or next_attempt_at <= clock_timestamp())
                            and (claim_until is null or claim_until < clock_timestamp())
                          order by created_at, id
                          limit :batchSize
                          for update skip locked
                        )
                        update outbox_event o
                        set claimed_by = :relayId,
                            claim_until = clock_timestamp() + interval '30 seconds',
                            publish_attempts = publish_attempts + 1,
                            next_attempt_at = null,
                            last_error = null
                        from candidates c
                        where o.id = c.id
                        returning o.id, o.aggregate_id, o.publish_attempts,
                                  o.payload->>'traceparent' as traceparent
                        """)
                .param("relayId", relayId)
                .param("batchSize", batchSize)
                .query((resultSet, rowNumber) -> new ClaimedOutboxRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("aggregate_id", UUID.class),
                        resultSet.getInt("publish_attempts"),
                        resultSet.getString("traceparent")))
                .list();
    }

    @Transactional
    public boolean markPublished(UUID eventId, String relayId) {
        return jdbc.sql("""
                        update outbox_event
                        set published_at = clock_timestamp(),
                            claimed_by = null,
                            claim_until = null,
                            last_error = null
                        where id = :eventId
                          and claimed_by = :relayId
                          and published_at is null
                        """)
                        .param("eventId", eventId)
                        .param("relayId", relayId)
                        .update()
                == 1;
    }

    @Transactional
    public boolean releaseFailed(
            UUID eventId,
            String relayId,
            String safeError,
            Duration retryDelay,
            boolean quarantine) {
        if (safeError == null || safeError.isBlank()) {
            throw new IllegalArgumentException("safeError must not be blank");
        }
        if (retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("retryDelay must be positive");
        }
        return jdbc.sql("""
                        update outbox_event
                        set claimed_by = null,
                            claim_until = null,
                            last_error = :safeError,
                            next_attempt_at = case
                              when :quarantine then null
                              else clock_timestamp()
                                   + (:retryDelayMillis * interval '1 millisecond')
                            end,
                            quarantined_at = case
                              when :quarantine then clock_timestamp()
                              else null
                            end
                        where id = :eventId
                          and claimed_by = :relayId
                          and published_at is null
                        """)
                        .param("eventId", eventId)
                        .param("relayId", relayId)
                        .param("safeError", safeError)
                        .param("retryDelayMillis", retryDelay.toMillis())
                        .param("quarantine", quarantine)
                        .update()
                == 1;
    }

    public long backlogCount() {
        return jdbc.sql("""
                        select count(*)
                        from outbox_event
                        where aggregate_type = 'execution'
                          and event_type = 'execution.requested.v1'
                          and published_at is null
                        """)
                .query(Long.class)
                .single();
    }

    public long quarantinedCount() {
        return jdbc.sql("""
                        select count(*)
                        from outbox_event
                        where aggregate_type = 'execution'
                          and event_type = 'execution.requested.v1'
                          and published_at is null
                          and quarantined_at is not null
                        """)
                .query(Long.class)
                .single();
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record OutboxRecord(UUID id, String eventType, String payloadJson, Instant createdAt) {}

    public record ClaimedOutboxRecord(
            UUID eventId, UUID executionId, int publishAttempts, String traceparent) {}
}
