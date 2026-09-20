package io.sentinelops.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.nimbusds.jwt.SignedJWT;
import io.sentinelops.api.execution.application.ExecutionApplicationService.CompletionCommand;
import io.sentinelops.api.execution.application.ExecutionTicketVerifier;
import io.sentinelops.api.execution.application.InvalidExecutionTicket;
import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

class ExecutionClaimIT extends ExecutionFixtureSupport {

    @Autowired private IncidentApplicationService incidents;
    @Autowired private ObjectMapper objectMapper;
    @MockitoSpyBean private ExecutionTicketVerifier ticketVerifier;

    @Test
    void claimAndCompletionReplayReturnTheSameResponseWithoutDuplicateAttempt() {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "idempotent-control-create-" + fixture.proposalId(),
                fixture.operator());
        String principalKey = "https://issuer.sentinelops.test\u001fexecutor-idempotent";
        String claimKey = "claim-" + created.id();

        var firstClaim = executions.claim(
                created.id(), "executor-idempotent", principalKey, claimKey);
        var replayedClaim = executions.claim(
                created.id(), "executor-idempotent", principalKey, claimKey);

        assertThat(replayedClaim).isEqualTo(firstClaim);
        var completion = new CompletionCommand(
                "recover-one",
                1,
                "demo-http",
                "1.0.0",
                "idempotent-request-hash",
                Map.of("changed", true));
        String completionKey = "complete-" + created.id();
        var firstCompletion = executions.complete(
                created.id(),
                "executor-idempotent",
                firstClaim.fencingToken(),
                firstClaim.ticket(),
                completion,
                principalKey,
                completionKey);
        var replayedClaimAfterCompletion = executions.claim(
                created.id(), "executor-idempotent", principalKey, claimKey);

        assertThat(replayedClaimAfterCompletion).isEqualTo(firstClaim);
        assertThatThrownBy(() -> executions.claim(
                        created.id(),
                        "executor-idempotent",
                        principalKey,
                        claimKey + "-redelivery"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("execution_not_claimable"));
        doThrow(new InvalidExecutionTicket("expired test ticket"))
                .when(ticketVerifier)
                .verify(anyString(), anyString(), anyString(), any(Instant.class));
        var replayedCompletion = executions.complete(
                created.id(),
                "executor-idempotent",
                firstClaim.fencingToken(),
                firstClaim.ticket(),
                completion,
                principalKey,
                completionKey);

        assertThat(replayedCompletion).isEqualTo(firstCompletion);
        assertThatThrownBy(() -> executions.complete(
                        created.id(),
                        "executor-idempotent",
                        firstClaim.fencingToken(),
                        firstClaim.ticket(),
                        completion,
                        principalKey,
                        completionKey + "-new"))
                .isInstanceOf(InvalidExecutionTicket.class);
        assertThat(jdbc.sql("select count(*) from execution_attempt where execution_id = :id")
                        .param("id", created.id())
                        .query(Integer.class)
                        .single())
                .isOne();
    }

    @Test
    void expiredApprovalEscalatesAnUnclaimedExecutionAndItsIncident() {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "expired-approval-create-" + fixture.proposalId(),
                fixture.operator());
        jdbc.sql("""
                        update approval_request
                        set expires_at = clock_timestamp() - interval '1 second'
                        where id = :approvalId
                        """)
                .param("approvalId", fixture.approvalId())
                .update();

        assertThatThrownBy(() -> executions.claim(created.id(), "executor-expired-approval"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("execution_authorization_invalidated"));

        assertInvalidExecutionWasEscalated(created.id(), fixture.incidentId());
    }

    @Test
    @Transactional
    void retiredRunbookEscalatesAnUnclaimedExecutionAndItsIncident() {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "retired-runbook-create-" + fixture.proposalId(),
                fixture.operator());
        jdbc.sql("""
                        update runbook_version
                        set lifecycle = 'retired'
                        where id = :runbookVersionId
                        """)
                .param("runbookVersionId", fixture.runbookVersionId())
                .update();

        assertThatThrownBy(() -> executions.claim(created.id(), "executor-retired-runbook"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("execution_authorization_invalidated"));

        assertInvalidExecutionWasEscalated(created.id(), fixture.incidentId());
    }

    @Test
    void twoConcurrentExecutorsProduceExactlyOneValidClaim() throws Exception {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "concurrent-claim-create-" + fixture.proposalId(),
                fixture.operator());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return executions.claim(created.id(), "executor-concurrent-a");
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return executions.claim(created.id(), "executor-concurrent-b");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            int conflicts = 0;
            for (var result : java.util.List.of(first, second)) {
                try {
                    var claim = result.get(10, TimeUnit.SECONDS);
                    assertThat(claim.fencingToken()).isEqualTo(1);
                    assertThat(claim.ticket()).isNotBlank();
                    successes++;
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause())
                            .isInstanceOfSatisfying(ApiProblemException.class,
                                    problem -> assertThat(problem.errorCode())
                                            .isEqualTo("execution_lease_active"));
                    conflicts++;
                }
            }
            assertThat(successes).isOne();
            assertThat(conflicts).isOne();
        }
    }

    @Test
    void heartbeatKeepsALongRunningExecutionLeased() {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "heartbeat-lease-create-" + fixture.proposalId(),
                fixture.operator());
        var claim = executions.claim(created.id(), "executor-with-heartbeat");

        var heartbeat = executions.heartbeat(
                created.id(),
                "executor-with-heartbeat",
                claim.fencingToken(),
                claim.ticket());
        var secondHeartbeat = executions.heartbeat(
                created.id(),
                "executor-with-heartbeat",
                claim.fencingToken(),
                heartbeat.ticket());

        assertThat(heartbeat.leaseUntil()).isAfter(claim.leaseUntil());
        assertThat(heartbeat.ticket()).isNotBlank().isNotEqualTo(claim.ticket());
        assertThat(secondHeartbeat.ticket())
                .isNotBlank()
                .isNotEqualTo(heartbeat.ticket());
        assertThatThrownBy(() -> executions.heartbeat(
                        created.id(),
                        "executor-with-heartbeat",
                        claim.fencingToken(),
                        claim.ticket()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("STALE_FENCING_TOKEN"));
        assertThatThrownBy(() -> executions.claim(created.id(), "executor-reclaimer"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("execution_lease_active"));
    }

    @Test
    void retryingAnActiveLeaseConflictCannotRecoverAnotherDeliverysTicket() {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "active-lease-retry-create-" + fixture.proposalId(),
                fixture.operator());
        String executorId = "shared-executor-identity";
        executions.claim(created.id(), executorId);
        String principalKey = "https://issuer.sentinelops.test\u001f" + executorId;
        String redeliveryKey = "active-redelivery-" + created.id();

        assertThatThrownBy(() -> executions.claim(
                        created.id(), executorId, principalKey, redeliveryKey))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("execution_lease_active"));
        jdbc.sql("""
                        update idempotency_record
                        set state = 'started', response_status = null, response_body = null
                        where principal_key = :principalKey
                          and route_key = 'POST:/internal/v1/executions/{id}:claim'
                          and idempotency_key = :idempotencyKey
                        """)
                .param("principalKey", principalKey)
                .param("idempotencyKey", redeliveryKey)
                .update();

        assertThatThrownBy(() -> executions.claim(
                        created.id(), executorId, principalKey, redeliveryKey))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("execution_lease_active"));
    }

    @Test
    void expiredLeaseCanBeReclaimedAndRejectsTheStaleFencingToken() {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "claim-create-" + fixture.proposalId(),
                fixture.operator());
        var first = executions.claim(created.id(), "executor-a");
        assertThat(first.fencingToken()).isEqualTo(1);
        assertThat(first.ticket()).isNotBlank();

        jdbc.sql("update execution set lease_until = clock_timestamp() - interval '1 second' where id = :id")
                .param("id", created.id())
                .update();
        var reclaimed = executions.claim(created.id(), "executor-b");
        assertThat(reclaimed.fencingToken()).isEqualTo(2);

        var completion = new CompletionCommand(
                "recover-one",
                1,
                "demo-http",
                "1.0.0",
                "request-hash",
                Map.of("changed", true));
        assertThatThrownBy(() -> executions.complete(
                        created.id(),
                        "executor-a",
                        first.fencingToken(),
                        first.ticket(),
                        completion))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo("STALE_FENCING_TOKEN"));

        var completed = executions.complete(
                created.id(),
                "executor-b",
                reclaimed.fencingToken(),
                reclaimed.ticket(),
                completion);
        assertThat(completed.status().databaseValue()).isEqualTo("verifying");
        assertThat(jdbc.sql("select count(*) from execution_attempt where execution_id = :id")
                        .param("id", created.id())
                        .query(Integer.class)
                        .single())
                .isOne();
        assertThat(jdbc.sql("select status from incident where id = :id")
                        .param("id", fixture.incidentId())
                        .query(String.class)
                        .single())
                .isEqualTo("verifying");
    }

    @Test
    void completionCannotChangeTheSignedStepOrAdapter() {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "signed-step-create-" + fixture.proposalId(),
                fixture.operator());
        var claim = executions.claim(created.id(), "executor-signed-step");
        var tampered = new CompletionCommand(
                "different-step",
                1,
                "different-adapter",
                "1.0.0",
                "tampered-request",
                Map.of("changed", true));

        assertThatThrownBy(() -> executions.complete(
                        created.id(),
                        "executor-signed-step",
                        claim.fencingToken(),
                        claim.ticket(),
                        tampered))
                .isInstanceOf(InvalidExecutionTicket.class);
        assertThat(jdbc.sql("select count(*) from execution_attempt where execution_id = :id")
                        .param("id", created.id())
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(executionStatus(created.id())).isEqualTo("running");
    }

    @Test
    void claimSignsTheApprovedTargetSnapshotAfterCatalogChanges() throws Exception {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "target-snapshot-create-" + fixture.proposalId(),
                fixture.operator());
        jdbc.sql("""
                        update service_catalog
                        set execution_target_aliases = '{"primary":"other-checkout"}'::jsonb
                        where id = :serviceId
                        """)
                .param("serviceId", fixture.serviceId())
                .update();

        try {
            var claim = executions.claim(created.id(), "executor-target-snapshot");

            assertThat(SignedJWT.parse(claim.ticket())
                            .getJWTClaimsSet()
                            .getStringClaim("target"))
                    .isEqualTo("demo-checkout");
        } finally {
            jdbc.sql("""
                            update service_catalog
                            set execution_target_aliases = '{"primary":"demo-checkout"}'::jsonb
                            where id = :serviceId
                            """)
                    .param("serviceId", fixture.serviceId())
                    .update();
        }
    }

    @Test
    void claimRetryRecoversAfterResponsePersistenceFailsPostCommit() {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "recoverable-claim-create-" + fixture.proposalId(),
                fixture.operator());
        String principalKey = "https://issuer.sentinelops.test\u001fexecutor-recovery";
        String claimKey = "recoverable-claim-" + created.id();
        jdbc.sql("""
                        create function reject_claim_completion_test() returns trigger
                        language plpgsql as $$
                        begin
                          if NEW.route_key = 'POST:/internal/v1/executions/{id}:claim'
                             and NEW.state = 'completed' then
                            raise exception 'forced claim response persistence failure';
                          end if;
                          return NEW;
                        end;
                        $$
                        """)
                .update();
        jdbc.sql("""
                        create trigger reject_claim_completion_test
                        before update on idempotency_record
                        for each row execute function reject_claim_completion_test()
                        """)
                .update();
        try {
            assertThatThrownBy(() -> executions.claim(
                            created.id(),
                            "executor-recovery",
                            principalKey,
                            claimKey))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.sql("drop trigger reject_claim_completion_test on idempotency_record").update();
            jdbc.sql("drop function reject_claim_completion_test()").update();
        }

        var recovered = executions.claim(
                created.id(), "executor-recovery", principalKey, claimKey);

        assertThat(recovered.fencingToken()).isOne();
        assertThat(recovered.ticket()).isNotBlank();
        assertThat(jdbc.sql("select fencing_token from execution where id = :id")
                        .param("id", created.id())
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void recoverySignalClosesPendingExecutionWithoutIssuingATicket() {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "pending-recovery-create-" + fixture.proposalId(),
                fixture.operator());

        incidents.ingest(recoveryAlert(fixture, "pending-recovery"));

        assertThat(executionStatus(created.id())).isEqualTo("verifying");
        assertThatThrownBy(() -> executions.claim(created.id(), "executor-after-recovery"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("execution_not_claimable"));
    }

    @Test
    void recoverySignalMarksRunningExecutionUnknownAndRejectsLateCompletion() {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "running-recovery-create-" + fixture.proposalId(),
                fixture.operator());
        var claim = executions.claim(created.id(), "executor-before-recovery");

        incidents.ingest(recoveryAlert(fixture, "running-recovery"));

        assertThat(executionStatus(created.id())).isEqualTo("unknown");
        var completion = new CompletionCommand(
                "recover-one",
                1,
                "demo-http",
                "1.0.0",
                "late-result",
                Map.of("changed", true));
        assertThatThrownBy(() -> executions.complete(
                        created.id(),
                        "executor-before-recovery",
                        claim.fencingToken(),
                        claim.ticket(),
                        completion))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isIn("STALE_FENCING_TOKEN", "execution_not_running"));
    }

    @Test
    void recoveryAndClaimRaceAlwaysClosesTheExecutionWithoutDeadlock() throws Exception {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "claim-recovery-race-create-" + fixture.proposalId(),
                fixture.operator());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var claim = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    executions.claim(created.id(), "executor-claim-recovery-race");
                    return "claimed";
                } catch (ApiProblemException conflict) {
                    return conflict.errorCode();
                }
            });
            var recovery = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return incidents.ingest(recoveryAlert(fixture, "claim-recovery-race"));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(claim.get(10, TimeUnit.SECONDS))
                    .isIn("claimed", "execution_not_claimable");
            assertThat(recovery.get(10, TimeUnit.SECONDS).status().databaseValue())
                    .isEqualTo("verifying");
        }
        assertThat(executionStatus(created.id())).isIn("verifying", "unknown");
    }

    @Test
    void recoveryAndCompletionRaceEndsInAClosedExecutionWithoutDeadlock() throws Exception {
        var fixture = approvedFixture();
        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "complete-recovery-race-create-" + fixture.proposalId(),
                fixture.operator());
        var claim = executions.claim(created.id(), "executor-complete-recovery-race");
        var completion = new CompletionCommand(
                "recover-one",
                1,
                "demo-http",
                "1.0.0",
                "race-result",
                Map.of("changed", true));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var complete = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    executions.complete(
                            created.id(),
                            "executor-complete-recovery-race",
                            claim.fencingToken(),
                            claim.ticket(),
                            completion);
                    return "completed";
                } catch (ApiProblemException conflict) {
                    return conflict.errorCode();
                }
            });
            var recovery = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return incidents.ingest(recoveryAlert(fixture, "complete-recovery-race"));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(complete.get(10, TimeUnit.SECONDS))
                    .isIn("completed", "STALE_FENCING_TOKEN", "execution_not_running");
            assertThat(recovery.get(10, TimeUnit.SECONDS).status().databaseValue())
                    .isEqualTo("verifying");
        }
        assertThat(executionStatus(created.id())).isIn("verifying", "unknown");
    }

    private AlertEnvelope recoveryAlert(Fixture fixture, String delivery) {
        var payload = objectMapper.createObjectNode()
                .put("serviceKey", "checkout-api")
                .put("fingerprint", fixture.fingerprint())
                .put("status", "resolved");
        return new AlertEnvelope(
                "test-alertmanager",
                delivery + '-' + fixture.incidentId(),
                "checkout-api",
                fixture.fingerprint(),
                "Checkout recovered",
                "sev2",
                AlertEnvelope.AlertStatus.RESOLVED,
                payload);
    }

    private String executionStatus(java.util.UUID executionId) {
        return jdbc.sql("select status from execution where id = :id")
                .param("id", executionId)
                .query(String.class)
                .single();
    }

    private void assertInvalidExecutionWasEscalated(
            java.util.UUID executionId, java.util.UUID incidentId) {
        assertThat(executionStatus(executionId)).isEqualTo("escalated");
        assertThat(jdbc.sql("select status from incident where id = :id")
                        .param("id", incidentId)
                        .query(String.class)
                        .single())
                .isEqualTo("escalated");
        assertThat(jdbc.sql("""
                                select count(*)
                                from incident_event
                                where incident_id = :incidentId
                                  and event_type = 'execution_authorization_invalidated'
                                """)
                        .param("incidentId", incidentId)
                        .query(Integer.class)
                        .single())
                .isOne();
    }
}
