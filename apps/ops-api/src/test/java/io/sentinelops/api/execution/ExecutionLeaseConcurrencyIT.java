package io.sentinelops.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.api.execution.adapter.out.persistence.ExecutionStore;
import io.sentinelops.api.execution.domain.ExecutionStatus;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

class ExecutionLeaseConcurrencyIT extends ExecutionFixtureSupport {

    @Autowired private ExecutionStore store;

    @Test
    void onlyOneOfEightConcurrentClaimsReceivesTheCurrentFencingToken() throws Exception {
        var fixture = approvedFixture();
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "eight-way-claim-" + fixture.proposalId(), fixture.operator());
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var attempts = new ArrayList<java.util.concurrent.Future<Long>>();

        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 8; index++) {
                String executorId = "executor-race-" + index;
                attempts.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Concurrent claim did not start");
                    }
                    return executions.claim(execution.id(), executorId).fencingToken();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int claimed = 0;
            int conflicts = 0;
            for (var attempt : attempts) {
                try {
                    assertThat(attempt.get(15, TimeUnit.SECONDS)).isEqualTo(1);
                    claimed++;
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause())
                            .isInstanceOfSatisfying(ApiProblemException.class,
                                    problem -> assertThat(problem.errorCode())
                                            .isEqualTo("execution_lease_active"));
                    conflicts++;
                }
            }
            assertThat(claimed).isOne();
            assertThat(conflicts).isEqualTo(7);
        }
    }

    @Test
    void completionSqlRejectsAnExpiredLeaseEvenWithMatchingOwnerAndFence() {
        var fixture = approvedFixture();
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "expired-completion-" + fixture.proposalId(), fixture.operator());
        var claim = executions.claim(execution.id(), "executor-expired-result");
        jdbc.sql("update execution set lease_until = clock_timestamp() - interval '1 second' where id = :id")
                .param("id", execution.id()).update();

        assertThatThrownBy(() -> store.updateAfterAttempt(execution.id(),
                ExecutionStatus.FAILED, "executor-expired-result",
                claim.fencingToken(), Instant.now()))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(jdbc.sql("select status from execution where id = :id")
                .param("id", execution.id()).query(String.class).single())
                .isEqualTo("running");
    }

    @Test
    void heartbeatNeverExtendsARevokedExecution() {
        var fixture = approvedFixture();
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "revoked-heartbeat-" + fixture.proposalId(), fixture.operator());
        var claim = executions.claim(execution.id(), "executor-revoked");
        jdbc.sql("update approval_request set status = 'expired' where id = :id")
                .param("id", fixture.approvalId()).update();

        assertThatThrownBy(() -> executions.heartbeat(execution.id(), "executor-revoked",
                claim.fencingToken(), claim.ticket()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("STALE_FENCING_TOKEN"));
        assertThat(jdbc.sql("select fencing_token from execution where id = :id")
                .param("id", execution.id()).query(Long.class).single())
                .isEqualTo(claim.fencingToken());
    }
}
