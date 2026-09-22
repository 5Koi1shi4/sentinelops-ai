package io.sentinelops.api.knowledge.adapter.out.persistence;

import io.sentinelops.api.knowledge.application.KnowledgeChunker;
import io.sentinelops.api.knowledge.application.KnowledgeQuery;
import io.sentinelops.api.shared.id.UuidV7Generator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class KnowledgeStore {
    private static final String SELECT = """
            select rv.*, r.runbook_key, r.service_id, r.display_name runbook_display_name,
                   r.owner_team runbook_owner_team from runbook_version rv
            join runbook r on r.id=rv.runbook_id
            """;
    private final JdbcClient jdbc;
    private final JdbcTemplate batches;
    private final ObjectMapper json;
    private final UuidV7Generator ids;

    public KnowledgeStore(JdbcClient jdbc, JdbcTemplate batches, ObjectMapper json, UuidV7Generator ids) {
        this.jdbc = jdbc; this.batches = batches; this.json = json; this.ids = ids;
    }

    public Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " where rv.id=:id").param("id", id).query(this::row).optional();
    }
    public List<Row> findPair(UUID first, UUID second) {
        // Both sides use one PostgreSQL statement snapshot, including a self comparison.
        return jdbc.sql(SELECT + " where rv.id in (:first, :second)")
                .param("first", first).param("second", second).query(this::row).list();
    }
    public boolean serviceExists(UUID serviceId) {
        return jdbc.sql("select exists(select 1 from service_catalog where id=:id)")
                .param("id", serviceId).query(Boolean.class).single();
    }
    public Optional<Row> lock(UUID id) {
        return jdbc.sql(SELECT + " where rv.id=:id for update of rv").param("id", id).query(this::row).optional();
    }
    public Optional<Runbook> findRunbook(String key) {
        return jdbc.sql("select id,service_id,display_name,owner_team from runbook where runbook_key=:key")
                .param("key", key).query((rs, index) -> new Runbook(rs.getObject("id", UUID.class),
                        rs.getObject("service_id", UUID.class), rs.getString("display_name"), rs.getString("owner_team"))).optional();
    }
    public Runbook createAndLockRunbook(String key, UUID service, String displayName, String ownerTeam) {
        jdbc.sql("""
                insert into runbook(id,runbook_key,service_id,display_name,owner_team,created_at,updated_at)
                values(:id,:key,:service,:name,:team,clock_timestamp(),clock_timestamp())
                on conflict(runbook_key) do nothing
                """).param("id", ids.generate()).param("key", key).param("service", service)
                .param("name", displayName).param("team", ownerTeam).update();
        return jdbc.sql("select id,service_id,display_name,owner_team from runbook where runbook_key=:key for update")
                .param("key", key).query((rs, index) -> new Runbook(rs.getObject("id", UUID.class),
                        rs.getObject("service_id", UUID.class), rs.getString("display_name"), rs.getString("owner_team"))).single();
    }
    public List<Row> versions(UUID runbookId, int after, int limit, boolean includeAdministrative) {
        String lifecycle = includeAdministrative ? "" : " and rv.lifecycle='published'";
        return jdbc.sql(SELECT + " where rv.runbook_id=:id and rv.version_number>:after" + lifecycle
                        + " order by rv.version_number limit :limit")
                .param("id", runbookId).param("after", after).param("limit", limit).query(this::row).list();
    }
    public List<RunbookSummaryRow> runbookSummaries(String afterKey, int limit, boolean includeAdministrative,
                                                     boolean platformAdmin, java.util.Set<UUID> serviceIds) {
        if (!platformAdmin && serviceIds.isEmpty()) return List.of();
        String lifecycle = includeAdministrative ? "" : " and rv.lifecycle='published'";
        String serviceScope = platformAdmin ? "" : " and r.service_id in (:serviceIds)";
        String cursor = afterKey == null ? "" : " and r.runbook_key>:afterKey";
        String sql = """
                select r.id, r.runbook_key, r.service_id, s.service_key, r.display_name, r.owner_team,
                       latest.id latest_version_id, latest.version_number latest_version_number,
                       latest.lifecycle, latest.risk_level
                from runbook r
                join service_catalog s on s.id=r.service_id
                join lateral (
                    select rv.id, rv.version_number, rv.lifecycle, rv.risk_level
                    from runbook_version rv
                    where rv.runbook_id=r.id%s
                    order by rv.version_number desc
                    limit 1
                ) latest on true
                where true%s%s
                order by r.runbook_key
                limit :limit
                """.formatted(lifecycle, cursor, serviceScope);
        var query = jdbc.sql(sql).param("limit", limit);
        if (afterKey != null) query = query.param("afterKey", afterKey);
        if (!platformAdmin) query = query.param("serviceIds", serviceIds);
        return query.query((rs, index) -> new RunbookSummaryRow(
                rs.getObject("id", UUID.class), rs.getString("runbook_key"), rs.getObject("service_id", UUID.class),
                rs.getString("service_key"),
                rs.getString("display_name"), rs.getString("owner_team"), rs.getObject("latest_version_id", UUID.class),
                rs.getInt("latest_version_number"), rs.getString("lifecycle"), rs.getString("risk_level"))).list();
    }
    public Row insertDraft(UUID runbookId, UUID actor, JsonNode definition, String checksum, String markdown) {
        UUID id = ids.generate();
        jdbc.sql("""
                insert into runbook_version(id,runbook_id,version_number,lifecycle,risk_level,adapter_id,
                    definition,definition_checksum,markdown,author_principal_id,last_editor_principal_id,created_at)
                select :id,:runbook,coalesce(max(version_number),0)+1,'draft',:risk,:adapter,
                    cast(:definition as jsonb),:checksum,:markdown,:actor,:actor,clock_timestamp()
                from runbook_version where runbook_id=:runbook
                """).param("id", id).param("runbook", runbookId).param("actor", actor)
                .param("risk", definition.path("risk").asString().toLowerCase(java.util.Locale.ROOT))
                .param("adapter", definition.path("adapterId").asString()).param("definition", json.writeValueAsString(definition))
                .param("checksum", checksum).param("markdown", markdown).update();
        return find(id).orElseThrow();
    }
    public Row updateDraft(UUID id, UUID actor, JsonNode definition, String checksum, String markdown) {
        jdbc.sql("""
                update runbook_version set definition=cast(:definition as jsonb),definition_checksum=:checksum,
                    risk_level=:risk,adapter_id=:adapter,markdown=:markdown,last_editor_principal_id=:actor,
                    reviewer_principal_id=null,reviewed_at=null,revision=revision+1
                where id=:id and lifecycle='draft'
                """).param("id", id).param("actor", actor).param("definition", json.writeValueAsString(definition))
                .param("checksum", checksum).param("markdown", markdown)
                .param("risk", definition.path("risk").asString().toLowerCase(java.util.Locale.ROOT))
                .param("adapter", definition.path("adapterId").asString()).update();
        return find(id).orElseThrow();
    }
    public Row review(UUID id, UUID actor) {
        jdbc.sql("update runbook_version set reviewer_principal_id=:actor,reviewed_at=clock_timestamp(),revision=revision+1 where id=:id and lifecycle='draft'")
                .param("id", id).param("actor", actor).update();
        return find(id).orElseThrow();
    }
    public Row publish(UUID id) {
        jdbc.sql("update runbook_version set lifecycle='published',published_at=clock_timestamp(),revision=revision+1 where id=:id and lifecycle='draft'")
                .param("id", id).update();
        return find(id).orElseThrow();
    }
    public void insertChunks(Row row, List<KnowledgeChunker.Chunk> chunks, List<float[]> vectors, String model) {
        var indexes = java.util.stream.IntStream.range(0, chunks.size()).boxed().toList();
        batches.batchUpdate("""
                insert into knowledge_chunk(id,runbook_version_id,service_id,chunk_no,content,embedding,embedding_model,content_hash,created_at)
                values(?,?,?,?,?,cast(? as vector),?,?,clock_timestamp())
                """, indexes, 100, (statement, index) -> {
                    var chunk = chunks.get(index);
                    statement.setObject(1, ids.generate()); statement.setObject(2, row.id());
                    statement.setObject(3, row.serviceId()); statement.setInt(4, chunk.chunkNo());
                    statement.setString(5, chunk.content()); statement.setString(6, KnowledgeQuery.vectorLiteral(vectors.get(index)));
                    statement.setString(7, model); statement.setString(8, chunk.contentHash());
                });
    }
    public void audit(Row row, String actor, String action, String beforeChecksum) {
        jdbc.sql("""
                insert into audit_record(id,service_id,actor_type,actor_id,action,resource_type,resource_id,
                    before_hash,after_hash,metadata,occurred_at)
                values(:id,:service,'user',:actor,:action,'runbook_version',:resource,:before,:after,
                    jsonb_build_object('revision',cast(:revision as bigint),'versionNumber',cast(:version as integer)),clock_timestamp())
                """).param("id", ids.generate()).param("service", row.serviceId()).param("actor", actor)
                .param("action", action).param("resource", row.id().toString()).param("before", beforeChecksum)
                .param("after", row.checksum()).param("revision", row.revision()).param("version", row.versionNumber()).update();
    }

    private Row row(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("runbook_id", UUID.class), rs.getString("runbook_key"),
                rs.getObject("service_id", UUID.class), rs.getString("runbook_display_name"), rs.getString("runbook_owner_team"),
                rs.getInt("version_number"), rs.getString("lifecycle"), rs.getLong("revision"),
                json.readTree(rs.getString("definition")), rs.getString("markdown"), rs.getString("definition_checksum"),
                rs.getObject("author_principal_id", UUID.class), rs.getObject("last_editor_principal_id", UUID.class),
                rs.getObject("reviewer_principal_id", UUID.class), instant(rs, "reviewed_at"), instant(rs, "published_at"));
    }
    private Instant instant(ResultSet rs, String column) throws SQLException {
        var time = rs.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }
    public record Runbook(UUID id, UUID serviceId, String displayName, String ownerTeam) {}
    public record RunbookSummaryRow(UUID id, String runbookKey, UUID serviceId, String serviceKey, String displayName, String ownerTeam,
                                    UUID latestVersionId, int latestVersionNumber, String lifecycle, String riskLevel) {}
    public record Row(UUID id, UUID runbookId, String runbookKey, UUID serviceId, String displayName, String ownerTeam,
            int versionNumber, String lifecycle,
            long revision, JsonNode definition, String markdown, String checksum, UUID authorId, UUID lastEditorId,
            UUID reviewerId, Instant reviewedAt, Instant publishedAt) {
        public Row { definition = definition.deepCopy(); }
        @Override public JsonNode definition() { return definition.deepCopy(); }
    }
}
