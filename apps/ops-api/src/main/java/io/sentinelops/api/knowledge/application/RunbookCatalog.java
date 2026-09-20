package io.sentinelops.api.knowledge.application;

import io.sentinelops.api.knowledge.domain.RiskLevel;
import io.sentinelops.api.knowledge.domain.RunbookVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class RunbookCatalog {

    private static final String SELECT_VERSION = """
            select rv.id, rv.runbook_id, r.runbook_key, r.service_id,
                   rv.version_number, rv.lifecycle, rv.risk_level, rv.adapter_id,
                   rv.definition::text, rv.definition_checksum, rv.published_at
            from runbook_version rv
            join runbook r on r.id = rv.runbook_id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public RunbookCatalog(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<RunbookVersion> findVersion(UUID versionId) {
        return jdbc.sql(SELECT_VERSION + " where rv.id = :versionId")
                .param("versionId", versionId)
                .query(this::mapVersion)
                .optional();
    }

    public Optional<RunbookVersion> findPublished(String runbookKey, UUID serviceId) {
        return jdbc.sql(SELECT_VERSION + """
                         where r.runbook_key = :runbookKey
                           and r.service_id = :serviceId
                           and rv.lifecycle = 'published'
                         order by rv.version_number desc
                         limit 1
                        """)
                .param("runbookKey", runbookKey)
                .param("serviceId", serviceId)
                .query(this::mapVersion)
                .optional();
    }

    private RunbookVersion mapVersion(ResultSet resultSet, int rowNumber) throws SQLException {
        var publishedAt = resultSet.getObject("published_at", OffsetDateTime.class);
        return new RunbookVersion(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("runbook_id", UUID.class),
                resultSet.getString("runbook_key"),
                resultSet.getObject("service_id", UUID.class),
                resultSet.getInt("version_number"),
                RunbookVersion.Lifecycle.valueOf(
                        resultSet.getString("lifecycle").toUpperCase(java.util.Locale.ROOT)),
                RiskLevel.fromDatabase(resultSet.getString("risk_level")),
                resultSet.getString("adapter_id"),
                objectMapper.readTree(resultSet.getString("definition")),
                resultSet.getString("definition_checksum"),
                publishedAt == null ? null : publishedAt.toInstant());
    }
}
