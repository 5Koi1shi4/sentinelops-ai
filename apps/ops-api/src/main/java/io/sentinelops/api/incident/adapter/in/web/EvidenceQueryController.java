package io.sentinelops.api.incident.adapter.in.web;

import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/incidents")
public class EvidenceQueryController {
    private static final Set<String> REDACTION_RULE_IDS = Set.of("json-pointer", "key-denylist", "max-depth", "string-length");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public EvidenceQueryController(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{id}/evidence")
    List<EvidenceView> evidence(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID serviceId = jdbc.sql("select service_id from incident where id = :id")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.NOT_FOUND,
                        "incident_not_found",
                        "The incident does not exist."));
        authorize(CurrentPrincipal.from(jwt), serviceId);
        return jdbc.sql("""
                        select id, source_type, source_ref, query_spec::text, redacted_payload::text,
                               content_hash, captured_at, truncated
                        from evidence_snapshot
                        where incident_id = :incidentId
                        order by captured_at, id
                        limit 100
                        """)
                .param("incidentId", id)
                .query(this::mapEvidence)
                .list();
    }

    @GetMapping("/{id}/evidence/{evidenceId}")
    EvidenceView evidence(@PathVariable UUID id, @PathVariable UUID evidenceId, @AuthenticationPrincipal Jwt jwt) {
        UUID serviceId = jdbc.sql("select service_id from incident where id = :id")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.NOT_FOUND,
                        "incident_not_found",
                        "The incident does not exist."));
        authorize(CurrentPrincipal.from(jwt), serviceId);
        return jdbc.sql("""
                        select id, source_type, source_ref, query_spec::text, redacted_payload::text,
                               content_hash, captured_at, truncated
                        from evidence_snapshot
                        where incident_id = :incidentId and id = :evidenceId
                        """)
                .param("incidentId", id)
                .param("evidenceId", evidenceId)
                .query(this::mapEvidence)
                .optional()
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.NOT_FOUND,
                        "evidence_not_found",
                        "The evidence snapshot does not exist for this incident."));
    }

    private void authorize(CurrentPrincipal principal, UUID serviceId) {
        if (!principal.canAccess(serviceId)
                || !principal.hasAnyRole(
                        PlatformRole.OBSERVER,
                        PlatformRole.ON_CALL_OPERATOR,
                        PlatformRole.SRE_APPROVER,
                        PlatformRole.RUNBOOK_ADMIN,
                        PlatformRole.PLATFORM_ADMIN)) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "access_denied",
                    "The principal cannot view evidence for this service.");
        }
    }

    private EvidenceView mapEvidence(ResultSet resultSet, int rowNumber) throws SQLException {
        JsonNode payload = objectMapper.readTree(resultSet.getString("redacted_payload"));
        JsonNode querySpec = objectMapper.readTree(resultSet.getString("query_spec"));
        int redactionCount = redactionCount(payload.path("redaction").path("count"));
        List<String> rules = redactionRules(payload.path("redaction").path("rules"));
        JsonNode safePayload = safePayload(payload);
        return new EvidenceView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source_type"),
                resultSet.getString("source_ref"),
                safePayload,
                resultSet.getString("content_hash"),
                resultSet.getObject("captured_at", OffsetDateTime.class).toInstant(),
                resultSet.getBoolean("truncated"),
                firstInstant(payload.path("from"), querySpec.path("from")),
                firstInstant(payload.path("to"), querySpec.path("to")),
                redactionCount,
                rules);
    }

    private static JsonNode safePayload(JsonNode payload) {
        if (payload instanceof tools.jackson.databind.node.ObjectNode object) {
            object.remove("parameters");
            object.remove("querySpec");
            object.remove("query_spec");
            object.remove("query");
            object.remove("redaction");
            return object;
        }
        return payload.deepCopy();
    }

    private static Instant instantOrNull(JsonNode value) {
        if (!value.isString() || value.asString().isBlank()) return null;
        try {
            return Instant.parse(value.asString());
        } catch (DateTimeParseException invalidHistoricalWindow) {
            return null;
        }
    }

    private static Instant firstInstant(JsonNode primary, JsonNode historicalQuerySpec) {
        Instant parsed = instantOrNull(primary);
        return parsed == null ? instantOrNull(historicalQuerySpec) : parsed;
    }

    private static int redactionCount(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) return 0;
        return value.asInt();
    }

    private static List<String> redactionRules(JsonNode value) {
        var rules = new TreeSet<String>();
        if (value.isArray()) {
            value.forEach(rule -> {
                if (rule.isString() && REDACTION_RULE_IDS.contains(rule.asString())) rules.add(rule.asString());
            });
        }
        return List.copyOf(rules);
    }

    public record EvidenceView(
            UUID id,
            String sourceType,
            String sourceRef,
            JsonNode redactedPayload,
            String contentHash,
            Instant capturedAt,
            boolean truncated,
            Instant from,
            Instant to,
            int redactionCount,
            List<String> redactionRules) {}
}
