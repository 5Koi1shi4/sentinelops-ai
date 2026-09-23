package io.sentinelops.api.audit.adapter.out.persistence;

import io.sentinelops.api.audit.domain.AuditRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AuditStore {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(AuditRecord record) {
        jdbc.sql("""
                insert into audit_record(id,service_id,actor_type,actor_id,action,
                  resource_type,resource_id,result,before_hash,after_hash,metadata,
                  trace_id,occurred_at)
                values(:id,:serviceId,:actorType,:actorId,:action,:resourceType,
                  :resourceId,:result,:beforeHash,:afterHash,cast(:metadata as jsonb),
                  :traceId,:occurredAt)
                """)
                .param("id", record.id()).param("serviceId", record.serviceId())
                .param("actorType", record.actorType()).param("actorId", record.actorId())
                .param("action", record.action()).param("resourceType", record.resourceType())
                .param("resourceId", record.resourceId()).param("result", record.result())
                .param("beforeHash", record.beforeHash()).param("afterHash", record.afterHash())
                .param("metadata", json.writeValueAsString(record.metadata()))
                .param("traceId", record.traceId())
                .param("occurredAt", OffsetDateTime.ofInstant(record.occurredAt(), java.time.ZoneOffset.UTC))
                .update();
    }

    public List<AuditRecord> list(UUID serviceId, Instant beforeTime, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("""
                select id,service_id,actor_type,actor_id,action,resource_type,resource_id,
                  result,before_hash,after_hash,metadata::text,trace_id,occurred_at
                from audit_record
                where true
                """);
        if (serviceId != null) sql.append(" and service_id=:serviceId");
        if (beforeTime != null) sql.append(" and (occurred_at,id)<(:beforeTime,:beforeId)");
        sql.append(" order by occurred_at desc,id desc limit :limit");
        var statement = jdbc.sql(sql.toString()).param("limit", limit);
        if (serviceId != null) statement = statement.param("serviceId", serviceId);
        if (beforeTime != null) statement = statement
                .param("beforeTime", OffsetDateTime.ofInstant(beforeTime, java.time.ZoneOffset.UTC))
                .param("beforeId", beforeId);
        return statement.query(this::map).list();
    }

    private AuditRecord map(ResultSet rs, int ignored) throws SQLException {
        JsonNode metadata = json.readTree(rs.getString("metadata"));
        return new AuditRecord(rs.getObject("id", UUID.class),
                rs.getObject("service_id", UUID.class), rs.getString("actor_type"),
                rs.getString("actor_id"), rs.getString("action"),
                rs.getString("resource_type"), rs.getString("resource_id"),
                rs.getString("result"), rs.getString("before_hash"),
                rs.getString("after_hash"), metadata, rs.getString("trace_id"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }
}
