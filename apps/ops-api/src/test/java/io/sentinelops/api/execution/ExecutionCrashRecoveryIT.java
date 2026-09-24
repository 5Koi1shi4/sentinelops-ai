package io.sentinelops.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.api.execution.application.ExecutionApplicationService.AttemptPhaseCommand;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionCrashRecoveryIT extends ExecutionFixtureSupport {

    @Test
    void nonIdempotentStepRetriesAfterConfirmedPreDispatchCrash() {
        var fixture = approvedFixture(nonIdempotentVersion());
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "prepared-crash-" + fixture.proposalId(), fixture.operator());
        var first = executions.claim(execution.id(), "executor-crashed-before-dispatch");
        phase(execution.id(), "executor-crashed-before-dispatch", first.ticket(),
                first.fencingToken(), "prepared");
        expireLease(execution.id());

        var second = executions.claim(execution.id(), "executor-after-crash");

        assertThat(second.fencingToken()).isEqualTo(2);
        assertThat(status(execution.id())).isEqualTo("running");
    }

    @Test
    void nonIdempotentDispatchWithoutResultEscalatesOnReclaim() {
        var fixture = approvedFixture(nonIdempotentVersion());
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "unknown-dispatch-" + fixture.proposalId(), fixture.operator());
        var first = executions.claim(execution.id(), "executor-crashed-after-dispatch");
        phase(execution.id(), "executor-crashed-after-dispatch", first.ticket(),
                first.fencingToken(), "prepared");
        phase(execution.id(), "executor-crashed-after-dispatch", first.ticket(),
                first.fencingToken(), "dispatched");
        expireLease(execution.id());

        assertThatThrownBy(() -> executions.claim(execution.id(), "executor-after-crash"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("execution_outcome_unknown"));
        assertThat(status(execution.id())).isEqualTo("escalated");
        assertThat(jdbc.sql("select status from incident where id = :id")
                .param("id", fixture.incidentId()).query(String.class).single())
                .isEqualTo("escalated");
        assertThat(jdbc.sql("""
                select count(*) from incident_event
                where incident_id = :id and event_type = 'execution_outcome_unknown'
                """).param("id", fixture.incidentId()).query(Integer.class).single())
                .isOne();
        assertThat(jdbc.sql("""
                select payload ->> 'instructions' from incident_event
                where incident_id = :id and event_type = 'execution_outcome_unknown'
                """).param("id", fixture.incidentId()).query(String.class).single())
                .contains("manual");
        assertThat(jdbc.sql("select outcome from execution_attempt where execution_id = :id")
                .param("id", execution.id()).query(String.class).single())
                .isEqualTo("unknown");
        assertThat(jdbc.sql("""
                select phase from execution_attempt_event
                where execution_id = :id order by occurred_at, id
                """).param("id", execution.id()).query(String.class).list())
                .containsExactly("prepared", "dispatched", "unknown_after_dispatch");
    }

    @Test
    void provenIdempotentDispatchCanBeReclaimedWithANewFence() {
        var fixture = approvedFixture();
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "idempotent-dispatch-" + fixture.proposalId(), fixture.operator());
        var first = executions.claim(execution.id(), "executor-first");
        phase(execution.id(), "executor-first", first.ticket(), first.fencingToken(), "prepared");
        phase(execution.id(), "executor-first", first.ticket(), first.fencingToken(), "dispatched");
        expireLease(execution.id());

        var reclaimed = executions.claim(execution.id(), "executor-second");

        assertThat(reclaimed.fencingToken()).isEqualTo(2);
        assertThat(status(execution.id())).isEqualTo("running");
    }

    @Test
    void nonIdempotentUnknownPhaseEscalatesImmediately() {
        var fixture = approvedFixture(nonIdempotentVersion());
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "unknown-immediate-" + fixture.proposalId(), fixture.operator());
        var claim = executions.claim(execution.id(), "executor-timeout");
        phase(execution.id(), "executor-timeout", claim.ticket(),
                claim.fencingToken(), "prepared");
        phase(execution.id(), "executor-timeout", claim.ticket(),
                claim.fencingToken(), "dispatched");

        phase(execution.id(), "executor-timeout", claim.ticket(),
                claim.fencingToken(), "unknown_after_dispatch");

        assertThat(status(execution.id())).isEqualTo("escalated");
        assertThat(jdbc.sql("select status from incident where id = :id")
                .param("id", fixture.incidentId()).query(String.class).single())
                .isEqualTo("escalated");
        assertThat(jdbc.sql("select count(*) from execution_attempt where execution_id = :id")
                .param("id", execution.id()).query(Integer.class).single())
                .isOne();
    }

    @Test
    void replaySafeUnknownPhaseRemainsClaimableAfterLeaseExpiry() {
        var fixture = approvedFixture();
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "replay-safe-unknown-" + fixture.proposalId(), fixture.operator());
        var first = executions.claim(execution.id(), "executor-timeout");
        phase(execution.id(), "executor-timeout", first.ticket(), first.fencingToken(), "prepared");
        phase(execution.id(), "executor-timeout", first.ticket(), first.fencingToken(), "dispatched");
        phase(execution.id(), "executor-timeout", first.ticket(), first.fencingToken(),
                "unknown_after_dispatch");

        assertThat(status(execution.id())).isEqualTo("running");
        expireLease(execution.id());
        assertThat(executions.claim(execution.id(), "executor-retry").fencingToken())
                .isEqualTo(2);
    }

    @Test
    void revokedAuthorizationAfterDispatchStillRecordsUnknownEffect() {
        var fixture = approvedFixture(nonIdempotentVersion());
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "revoked-after-dispatch-" + fixture.proposalId(), fixture.operator());
        var first = executions.claim(execution.id(), "executor-before-revocation");
        phase(execution.id(), "executor-before-revocation", first.ticket(),
                first.fencingToken(), "prepared");
        phase(execution.id(), "executor-before-revocation", first.ticket(),
                first.fencingToken(), "dispatched");
        jdbc.sql("update approval_request set expires_at = clock_timestamp() - interval '1 second' where id = :id")
                .param("id", fixture.approvalId()).update();
        expireLease(execution.id());

        assertThatThrownBy(() -> executions.claim(execution.id(), "executor-after-revocation"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("execution_outcome_unknown"));
        assertThat(status(execution.id())).isEqualTo("escalated");
        assertThat(jdbc.sql("select outcome from execution_attempt where execution_id = :id")
                .param("id", execution.id()).query(String.class).single())
                .isEqualTo("unknown");
        assertThat(jdbc.sql("""
                select phase from execution_attempt_event
                where execution_id = :id order by occurred_at, id
                """).param("id", execution.id()).query(String.class).list())
                .containsExactly("prepared", "dispatched", "unknown_after_dispatch");
        assertThat(jdbc.sql("""
                select payload ->> 'authorizationReason' from incident_event
                where incident_id = :id and event_type = 'execution_outcome_unknown'
                """).param("id", fixture.incidentId()).query(String.class).single())
                .isEqualTo("approval_expired");
    }

    private UUID nonIdempotentVersion() {
        UUID runbookId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.sql("""
                insert into runbook(id, runbook_key, service_id, display_name, owner_team,
                                    created_at, updated_at)
                select :id, :key, service_id, 'One shot fixture', owner_team,
                       clock_timestamp(), clock_timestamp()
                from runbook where runbook_key = 'RB-DB-POOL-03'
                """).param("id", runbookId)
                .param("key", "RB-ONCE-" + runbookId)
                .update();
        jdbc.sql("""
                insert into runbook_version(id, runbook_id, version_number, lifecycle,
                    risk_level, adapter_id, definition, definition_checksum,
                    author_principal_id, reviewer_principal_id, created_at, published_at)
                select :id, :runbookId, 1, 'published', risk_level,
                       'once-test', '{"steps":[{"stepId":"recover-one","operation":"one_shot"}]}'::jsonb,
                       'once-test-checksum', author_principal_id, reviewer_principal_id,
                       clock_timestamp(), clock_timestamp()
                from runbook_version where id = '0199a000-0000-7000-8000-000000000005'
                """).param("id", versionId).param("runbookId", runbookId).update();
        return versionId;
    }

    private void phase(UUID executionId, String owner, String ticket, long fence, String phase) {
        executions.recordAttemptPhase(executionId, owner, fence, ticket,
                new AttemptPhaseCommand("recover-one", (int) fence, phase, Map.of()),
                "https://issuer.sentinelops.test\u001f" + owner,
                executionId + ":" + fence + ":" + phase);
    }

    private void expireLease(UUID executionId) {
        jdbc.sql("update execution set lease_until = clock_timestamp() - interval '1 second' where id = :id")
                .param("id", executionId).update();
    }

    private String status(UUID executionId) {
        return jdbc.sql("select status from execution where id = :id")
                .param("id", executionId).query(String.class).single();
    }
}
