package io.sentinelops.api.incident.adapter.in.web;

import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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
                        select id, source_type, source_ref, redacted_payload::text,
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
        return new EvidenceView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source_type"),
                resultSet.getString("source_ref"),
                objectMapper.readTree(resultSet.getString("redacted_payload")),
                resultSet.getString("content_hash"),
                resultSet.getObject("captured_at", OffsetDateTime.class).toInstant(),
                resultSet.getBoolean("truncated"));
    }

    public record EvidenceView(
            UUID id,
            String sourceType,
            String sourceRef,
            JsonNode redactedPayload,
            String contentHash,
            Instant capturedAt,
            boolean truncated) {}
}
