package io.sentinelops.api.execution;

import static io.sentinelops.api.identity.application.PlatformRole.ON_CALL_OPERATOR;

import io.sentinelops.api.execution.application.ExecutionApplicationService;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

abstract class ExecutionFixtureSupport extends PostgresIntegrationTest {

    @Autowired protected ExecutionApplicationService executions;
    @Autowired protected JdbcClient jdbc;

    protected Fixture approvedFixture() {
        UUID runbookVersionId = jdbc.sql("""
                        select rv.id
                        from runbook_version rv
                        join runbook r on r.id = rv.runbook_id
                        where r.runbook_key = 'RB-DB-POOL-03'
                          and rv.version_number = 1
                        """)
                .query(UUID.class)
                .single();
        return approvedFixture(runbookVersionId);
    }

    protected Fixture approvedFixture(UUID runbookVersionId) {
        return approvedFixture(runbookVersionId, "demo-checkout");
    }

    protected Fixture approvedFixture(UUID runbookVersionId, String targetAlias) {
        UUID serviceId = jdbc.sql("select id from service_catalog where service_key = 'checkout-api'")
                .query(UUID.class)
                .single();
        UUID requesterId = jdbc.sql("select id from principal where subject = 'demo-author'")
                .query(UUID.class)
                .single();
        String suffix = UUID.randomUUID().toString();
        String fingerprint = "execution-" + suffix;
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        String proposalHash = "execution-proposal-" + suffix;
        var now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.sql("""
                        insert into incident(
                          id, service_id, fingerprint, title, severity, status,
                          version, next_event_seq, occurrence_count, opened_at, updated_at
                        ) values (
                          :id, :serviceId, :fingerprint, 'Execution fixture', 'sev2',
                          'awaiting_approval', 3, 1, 1, :now, :now
                        )
                        """)
                .param("id", incidentId)
                .param("serviceId", serviceId)
                .param("fingerprint", fingerprint)
                .param("now", now)
                .update();
        jdbc.sql("""
                        insert into diagnosis_run(
                          id, incident_id, requested_by_principal_id, incident_version,
                          engine_type, status, prompt_version, input_hash, started_at, completed_at
                        ) values (
                          :id, :incidentId, :requesterId, 0, 'deterministic', 'succeeded',
                          'fixture-v1', :inputHash, :now, :now
                        )
                        """)
                .param("id", runId)
                .param("incidentId", incidentId)
                .param("requesterId", requesterId)
                .param("inputHash", "input-" + suffix)
                .param("now", now)
                .update();
        jdbc.sql("""
                        insert into diagnosis_proposal(
                          id, diagnosis_run_id, incident_id, runbook_version_id, summary,
                          proposal_payload, proposal_hash, risk_level, created_at
                        ) values (
                          :id, :runId, :incidentId, :runbookVersionId, 'Execution fixture',
                          '{"parameters":{"replicas":1},"riskLevel":"R1"}'::jsonb,
                          :proposalHash, 'r1', :now
                        )
                        """)
                .param("id", proposalId)
                .param("runId", runId)
                .param("incidentId", incidentId)
                .param("runbookVersionId", runbookVersionId)
                .param("proposalHash", proposalHash)
                .param("now", now)
                .update();
        jdbc.sql("""
                        insert into approval_request(
                          id, incident_id, proposal_id, proposal_hash, requester_principal_id,
                          policy_version, required_approvals, independent_approver_required,
                          status, resource_version, target_alias,
                          created_at, expires_at, decided_at
                        ) values (
                          :id, :incidentId, :proposalId, :proposalHash, :requesterId,
                          'stage1-v1', 1, true, 'approved', 1,
                          :targetAlias, :createdAt, :expiresAt, :decidedAt
                        )
                        """)
                .param("id", approvalId)
                .param("incidentId", incidentId)
                .param("proposalId", proposalId)
                .param("proposalHash", proposalHash)
                .param("requesterId", requesterId)
                .param("targetAlias", targetAlias)
                .param("createdAt", now.minusMinutes(1))
                .param("expiresAt", now.plusMinutes(7))
                .param("decidedAt", now)
                .update();

        var operator = new CurrentPrincipal(
                "https://issuer.sentinelops.test",
                "operator-" + suffix,
                Set.of(ON_CALL_OPERATOR),
                Set.of(serviceId));
        return new Fixture(
                incidentId,
                proposalId,
                approvalId,
                proposalHash,
                runbookVersionId,
                serviceId,
                fingerprint,
                operator);
    }

    protected record Fixture(
            UUID incidentId,
            UUID proposalId,
            UUID approvalId,
            String proposalHash,
            UUID runbookVersionId,
            UUID serviceId,
            String fingerprint,
            CurrentPrincipal operator) {}
}
