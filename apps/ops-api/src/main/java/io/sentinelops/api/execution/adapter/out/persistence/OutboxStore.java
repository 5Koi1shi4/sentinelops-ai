package io.sentinelops.api.execution.adapter.out.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record OutboxRecord(UUID id, String eventType, String payloadJson, Instant createdAt) {}
}
