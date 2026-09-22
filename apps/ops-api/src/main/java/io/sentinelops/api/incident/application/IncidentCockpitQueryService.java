package io.sentinelops.api.incident.application;

import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.incident.application.IncidentCockpitView.ApprovalView;
import io.sentinelops.api.incident.application.IncidentCockpitView.DiagnosisView;
import io.sentinelops.api.incident.application.IncidentCockpitView.EvidenceReference;
import io.sentinelops.api.incident.application.IncidentCockpitView.ExecutionView;
import io.sentinelops.api.incident.domain.IncidentStatus;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class IncidentCockpitQueryService {

    private static final int MAX_EVIDENCE_REFERENCES = 100;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public IncidentCockpitQueryService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public IncidentCockpitView get(UUID incidentId, CurrentPrincipal principal) {
        IncidentSummary incident = findIncident(incidentId);
        authorize(principal, incident.serviceId());

        DiagnosisView diagnosis = findLatestDiagnosis(incidentId);
        List<EvidenceReference> evidence = diagnosis == null
                ? List.of()
                : findEvidenceReferences(diagnosis.id());
        ApprovalView approval = diagnosis == null ? null : findActiveApproval(diagnosis.id());
        ExecutionView execution = findLatestExecution(incidentId);
        return new IncidentCockpitView(incident, diagnosis, evidence, approval, execution);
    }

    private IncidentSummary findIncident(UUID incidentId) {
        return jdbc.sql("""
                        select p.incident_id, p.service_id, s.service_key, p.title, p.severity,
                               p.status, p.resource_version, p.occurrence_count,
                               p.opened_at, p.updated_at, p.resolved_at
                        from incident_projection p
                        join service_catalog s on s.id = p.service_id
                        where p.incident_id = :incidentId
                        """)
                .param("incidentId", incidentId)
                .query(this::mapIncident)
                .optional()
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.NOT_FOUND,
                        "incident_not_found",
                        "The incident does not exist."));
    }

    private DiagnosisView findLatestDiagnosis(UUID incidentId) {
        return jdbc.sql("""
                        select p.id, p.diagnosis_run_id, dr.incident_version, p.summary,
                               coalesce(p.proposal_payload -> 'hypotheses', '[]'::jsonb)::text
                                 as hypotheses,
                               coalesce(p.proposal_payload -> 'missingEvidence', '[]'::jsonb)::text
                                 as missing_evidence,
                               p.runbook_version_id, rv.version_number, r.runbook_key,
                               r.display_name as runbook_name,
                               coalesce(p.proposal_payload -> 'parameters', '{}'::jsonb)::text
                                 as parameters,
                               p.risk_level,
                               coalesce(p.proposal_payload -> 'expectedVerification', 'null'::jsonb)::text
                                 as expected_verification,
                               p.proposal_hash, p.created_at
                        from diagnosis_proposal p
                        join diagnosis_run dr on dr.id = p.diagnosis_run_id
                        left join runbook_version rv on rv.id = p.runbook_version_id
                        left join runbook r on r.id = rv.runbook_id
                        where p.incident_id = :incidentId
                        order by p.created_at desc, p.id desc
                        limit 1
                        """)
                .param("incidentId", incidentId)
                .query(this::mapDiagnosis)
                .optional()
                .orElse(null);
    }

    private List<EvidenceReference> findEvidenceReferences(UUID proposalId) {
        return jdbc.sql("""
                        select e.id, e.source_type, e.source_ref, e.content_hash,
                               e.captured_at, e.truncated
                        from diagnosis_proposal_evidence pe
                        join evidence_snapshot e on e.id = pe.evidence_snapshot_id
                        where pe.proposal_id = :proposalId
                        order by e.captured_at, e.id
                        limit :limit
                        """)
                .param("proposalId", proposalId)
                .param("limit", MAX_EVIDENCE_REFERENCES)
                .query((resultSet, rowNumber) -> new EvidenceReference(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("source_type"),
                        resultSet.getString("source_ref"),
                        resultSet.getString("content_hash"),
                        instant(resultSet, "captured_at"),
                        resultSet.getBoolean("truncated")))
                .list();
    }

    private ApprovalView findActiveApproval(UUID proposalId) {
        return jdbc.sql("""
                        select ar.id, ar.proposal_id, ar.proposal_hash, ar.target_alias,
                               ar.status, ar.resource_version, ar.required_approvals,
                               ar.requester_principal_id, p.subject as requester_subject,
                               p.display_name as requester_display_name,
                               ar.independent_approver_required, ar.created_at,
                               ar.expires_at, ar.decided_at,
                               (select count(*) from approval_decision d
                                where d.approval_request_id = ar.id and d.decision = 'approve')
                                 as approvals,
                               (select count(*) from approval_decision d
                                where d.approval_request_id = ar.id and d.decision = 'reject')
                                 as rejections
                        from approval_request ar
                        join principal p on p.id = ar.requester_principal_id
                        where ar.proposal_id = :proposalId
                          and ar.status in ('pending', 'approved')
                        order by ar.created_at desc, ar.id desc
                        limit 1
                        """)
                .param("proposalId", proposalId)
                .query((resultSet, rowNumber) -> new ApprovalView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("proposal_id", UUID.class),
                        resultSet.getString("proposal_hash"),
                        resultSet.getString("target_alias"),
                        enumName(resultSet.getString("status")),
                        resultSet.getLong("resource_version"),
                        resultSet.getInt("required_approvals"),
                        resultSet.getInt("approvals"),
                        resultSet.getInt("rejections"),
                        resultSet.getObject("requester_principal_id", UUID.class),
                        resultSet.getString("requester_subject"),
                        resultSet.getString("requester_display_name"),
                        resultSet.getBoolean("independent_approver_required"),
                        instant(resultSet, "created_at"),
                        instant(resultSet, "expires_at"),
                        nullableInstant(resultSet, "decided_at")))
                .optional()
                .orElse(null);
    }

    private ExecutionView findLatestExecution(UUID incidentId) {
        return jdbc.sql("""
                        select id, proposal_id, approval_request_id, status, target_alias,
                               fencing_token, created_at, updated_at, started_at, completed_at
                        from execution
                        where incident_id = :incidentId
                        order by created_at desc, id desc
                        limit 1
                        """)
                .param("incidentId", incidentId)
                .query((resultSet, rowNumber) -> new ExecutionView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("proposal_id", UUID.class),
                        resultSet.getObject("approval_request_id", UUID.class),
                        enumName(resultSet.getString("status")),
                        resultSet.getString("target_alias"),
                        resultSet.getLong("fencing_token"),
                        instant(resultSet, "created_at"),
                        instant(resultSet, "updated_at"),
                        nullableInstant(resultSet, "started_at"),
                        nullableInstant(resultSet, "completed_at")))
                .optional()
                .orElse(null);
    }

    private IncidentSummary mapIncident(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IncidentSummary(
                resultSet.getObject("incident_id", UUID.class),
                resultSet.getObject("service_id", UUID.class),
                resultSet.getString("service_key"),
                resultSet.getString("title"),
                resultSet.getString("severity"),
                IncidentStatus.fromDatabase(resultSet.getString("status")),
                resultSet.getLong("resource_version"),
                resultSet.getLong("occurrence_count"),
                instant(resultSet, "opened_at"),
                instant(resultSet, "updated_at"),
                nullableInstant(resultSet, "resolved_at"));
    }

    private DiagnosisView mapDiagnosis(ResultSet resultSet, int rowNumber) throws SQLException {
        Integer runbookVersion = resultSet.getObject("version_number", Integer.class);
        return new DiagnosisView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("diagnosis_run_id", UUID.class),
                resultSet.getLong("incident_version"),
                resultSet.getString("summary"),
                objectMapper.readTree(resultSet.getString("hypotheses")),
                objectMapper.readTree(resultSet.getString("missing_evidence")),
                resultSet.getObject("runbook_version_id", UUID.class),
                runbookVersion,
                resultSet.getString("runbook_key"),
                resultSet.getString("runbook_name"),
                objectMapper.readTree(resultSet.getString("parameters")),
                enumName(resultSet.getString("risk_level")),
                objectMapper.readTree(resultSet.getString("expected_verification")),
                resultSet.getString("proposal_hash"),
                instant(resultSet, "created_at"));
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
                    "The principal cannot view incidents for this service.");
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private String enumName(String databaseValue) {
        return databaseValue.toUpperCase(Locale.ROOT);
    }
}
