package io.sentinelops.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.sentinelops.api.execution.application.ExecutionApplicationService.CompletionCommand;
import io.sentinelops.api.execution.application.ExecutionVerificationService;
import io.sentinelops.api.execution.application.VerificationProbe;
import io.sentinelops.api.execution.application.VerificationProbe.VerificationResult;
import io.sentinelops.api.execution.adapter.out.persistence.VerificationStore;
import io.sentinelops.api.execution.domain.ExecutionStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ExecutionVerificationIT extends ExecutionFixtureSupport {

    @MockitoBean private VerificationProbe probe;

    @org.springframework.beans.factory.annotation.Autowired
    private ExecutionVerificationService verification;

    @org.springframework.beans.factory.annotation.Autowired
    private VerificationStore verificationStore;

    @Test
    void successfulObjectiveProbeResolvesTheIncidentAndExecution() {
        var fixture = approvedFixture();
        UUID executionId = completeExecution(fixture);
        when(probe.verify(any())).thenReturn(VerificationResult.succeeded(
                Map.of("status", "UP", "httpStatus", 200)));

        assertThat(verification.runPendingOnce()).isTrue();

        assertThat(incidentStatus(fixture.incidentId())).isEqualTo("resolved");
        assertThat(executionStatus(executionId)).isEqualTo("succeeded");
        assertThat(jdbc.sql("""
                                select count(*) from verification_attempt va
                                join verification_cycle vc on vc.id = va.cycle_id
                                where vc.incident_id = :incidentId
                                """)
                        .param("incidentId", fixture.incidentId())
                        .query(Integer.class)
                        .single())
                .isOne();
        assertThat(jdbc.sql("select resolved_at is not null from incident where id = :id")
                        .param("id", fixture.incidentId())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void firstFailedCycleReturnsToTriageAndSecondFailedCycleEscalates() {
        var fixture = approvedFixture();
        UUID executionId = completeExecution(fixture);
        when(probe.verify(any())).thenReturn(VerificationResult.failed(
                Map.of("status", "DOWN", "httpStatus", 503)));

        assertThat(verification.runPendingOnce()).isTrue();

        assertThat(incidentStatus(fixture.incidentId())).isEqualTo("triaging");
        assertThat(executionStatus(executionId)).isEqualTo("unknown");
        assertThat(verification.requestManual(
                                fixture.incidentId(),
                                incidentVersion(fixture.incidentId()),
                                "second-cycle-" + fixture.incidentId(),
                                "Checkout remains unavailable after the first verification cycle",
                                fixture.operator())
                        .status())
                .isEqualTo("verifying");

        assertThat(verification.runPendingOnce()).isTrue();

        assertThat(incidentStatus(fixture.incidentId())).isEqualTo("escalated");
        assertThat(jdbc.sql("""
                                select count(*) from verification_cycle
                                where incident_id = :incidentId and status = 'failed'
                                """)
                        .param("incidentId", fixture.incidentId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(jdbc.sql("""
                                select count(*) from verification_attempt va
                                join verification_cycle vc on vc.id = va.cycle_id
                                where vc.incident_id = :incidentId
                                """)
                        .param("incidentId", fixture.incidentId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(12);
        verify(probe, times(12)).verify(any());
    }

    @Test
    void executionBindsVerificationPolicyBeforeTheRunbookIsRetired() {
        UUID retiringRunbookVersionId = clonePublishedRunbook();
        var fixture = approvedFixture(retiringRunbookVersionId);
        completeExecution(fixture);
        jdbc.sql("""
                        update runbook_version
                        set lifecycle = 'retired'
                        where id = :runbookVersionId
                        """)
                .param("runbookVersionId", retiringRunbookVersionId)
                .update();
        when(probe.verify(any())).thenReturn(VerificationResult.succeeded(
                Map.of("status", "UP", "httpStatus", 200)));

        assertThat(verification.runPendingOnce()).isTrue();

        assertThat(incidentStatus(fixture.incidentId())).isEqualTo("resolved");
        assertThat(jdbc.sql("""
                                select runbook_version_id
                                from verification_cycle
                                where incident_id = :incidentId
                                """)
                        .param("incidentId", fixture.incidentId())
                        .query(UUID.class)
                        .single())
                .isEqualTo(retiringRunbookVersionId);
    }

    @Test
    void reclaimedCycleRejectsThePreviousClaimTokenEvenForTheSameWorkerId() {
        var fixture = approvedFixture();
        completeExecution(fixture);
        UUID firstToken = UUID.randomUUID();
        var first = verificationStore.claimNext(firstToken, "same-worker", 120)
                .orElseThrow();
        jdbc.sql("""
                        update verification_cycle
                        set claim_until = :expiredAt
                        where id = :cycleId
                        """)
                .param("expiredAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
                .param("cycleId", first.id())
                .update();
        UUID secondToken = UUID.randomUUID();
        var second = verificationStore.claimNext(secondToken, "same-worker", 120)
                .orElseThrow();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.claimToken()).isEqualTo(secondToken);
        assertThatThrownBy(() -> verificationStore.recordAttempt(
                        UUID.randomUUID(),
                        first.id(),
                        firstToken,
                        "same-worker",
                        1,
                        false,
                        "{\"status\":\"DOWN\"}",
                        120))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    @Test
    void supersededCycleDoesNotConsumeTheFirstFailureBudget() {
        var fixture = approvedFixture();
        completeExecution(fixture);
        var versionBumped = new AtomicBoolean();
        when(probe.verify(any())).thenAnswer(invocation -> {
            if (versionBumped.compareAndSet(false, true)) {
                jdbc.sql("""
                                update incident
                                set version = version + 1,
                                    occurrence_count = occurrence_count + 1,
                                    updated_at = clock_timestamp()
                                where id = :incidentId
                                """)
                        .param("incidentId", fixture.incidentId())
                        .update();
            }
            return VerificationResult.failed(Map.of("status", "DOWN"));
        });

        assertThat(verification.runPendingOnce()).isTrue();
        assertThat(incidentStatus(fixture.incidentId())).isEqualTo("verifying");
        assertThat(verification.runPendingOnce()).isTrue();

        assertThat(incidentStatus(fixture.incidentId())).isEqualTo("triaging");
        assertThat(jdbc.sql("""
                                select count(*)
                                from verification_cycle
                                where incident_id = :incidentId and status = 'superseded'
                                """)
                        .param("incidentId", fixture.incidentId())
                        .query(Integer.class)
                        .single())
                .isOne();
    }

    private UUID clonePublishedRunbook() {
        UUID cloneId = UUID.randomUUID();
        jdbc.sql("""
                        insert into runbook_version(
                          id, runbook_id, version_number, lifecycle, risk_level,
                          adapter_id, definition, definition_checksum,
                          author_principal_id, reviewer_principal_id,
                          created_at, published_at
                        )
                        select :cloneId, source.runbook_id,
                               (select max(existing.version_number) + 1
                                from runbook_version existing
                                where existing.runbook_id = source.runbook_id),
                               'published', source.risk_level, source.adapter_id,
                               source.definition, source.definition_checksum,
                               source.author_principal_id, source.reviewer_principal_id,
                               clock_timestamp(), clock_timestamp()
                        from runbook_version source
                        where source.id = :sourceId
                        """)
                .param("cloneId", cloneId)
                .param("sourceId", jdbc.sql("""
                                select rv.id
                                from runbook_version rv
                                join runbook r on r.id = rv.runbook_id
                                where r.runbook_key = 'RB-DB-POOL-03'
                                  and rv.version_number = 1
                                """)
                        .query(UUID.class)
                        .single())
                .update();
        return cloneId;
    }

    private UUID completeExecution(Fixture fixture) {
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "verification-create-" + fixture.proposalId(),
                fixture.operator());
        var claim = executions.claim(created.id(), "verification-executor");
        var completed = executions.complete(
                created.id(),
                "verification-executor",
                claim.fencingToken(),
                claim.ticket(),
                new CompletionCommand(
                        "recover-one",
                        1,
                        "demo-http",
                        "demo-http-v1",
                        "request-hash",
                        Map.of("changed", true)));
        assertThat(completed.status()).isEqualTo(ExecutionStatus.VERIFYING);
        return completed.id();
    }

    private String incidentStatus(UUID incidentId) {
        return jdbc.sql("select status from incident where id = :id")
                .param("id", incidentId)
                .query(String.class)
                .single();
    }

    private long incidentVersion(UUID incidentId) {
        return jdbc.sql("select version from incident where id = :id")
                .param("id", incidentId)
                .query(Long.class)
                .single();
    }

    private String executionStatus(UUID executionId) {
        return jdbc.sql("select status from execution where id = :id")
                .param("id", executionId)
                .query(String.class)
                .single();
    }
}
