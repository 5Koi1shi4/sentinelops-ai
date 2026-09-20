package io.sentinelops.api.execution;

import static io.sentinelops.api.execution.domain.ExecutionStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.api.shared.problem.ApiProblemException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.ObjectMapper;

class ExecutionCreationIT extends ExecutionFixtureSupport {

    @Test
    void createsExecutionAndMinimalOutboxAtomically() {
        var fixture = approvedFixture();

        var created = executions.create(
                fixture.incidentId(),
                fixture.proposalId(),
                3,
                "execute-" + fixture.proposalId(),
                fixture.operator());

        assertThat(created.status()).isEqualTo(PENDING);
        assertThat(executionCount(created.id())).isOne();
        var payload = jdbc.sql("""
                        select payload::text from outbox_event
                        where aggregate_type = 'execution' and aggregate_id = :id
                        """)
                .param("id", created.id())
                .query(String.class)
                .single();
        var json = new ObjectMapper().readTree(payload);
        assertThat(json.path("eventId").asString()).isNotBlank();
        assertThat(json.path("eventType").asString()).isEqualTo("execution.requested.v1");
        assertThat(json.path("executionId").asString()).isEqualTo(created.id().toString());
        assertThat(json.path("occurredAt").asString()).isNotBlank();
        assertThat(json.has("parameters")).isFalse();
        assertThat(json.has("target")).isFalse();
        assertThat(incidentStatus(fixture.incidentId())).isEqualTo("executing");
        assertThat(created.incidentVersion()).isEqualTo(4);
    }

    @Test
    void duplicateCreateReturnsTheSameExecutionWithoutDuplicateOutbox() {
        var fixture = approvedFixture();
        String key = "execute-replay-" + fixture.proposalId();

        var first = executions.create(
                fixture.incidentId(), fixture.proposalId(), 3, key, fixture.operator());
        var replay = executions.create(
                fixture.incidentId(), fixture.proposalId(), 3, key, fixture.operator());

        assertThat(replay).isEqualTo(first);
        assertThat(executionCount(first.id())).isOne();
        assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id = :id")
                        .param("id", first.id())
                        .query(Integer.class)
                        .single())
                .isOne();
    }

    @Test
    void changedTargetAfterApprovalCannotCreateExecution() {
        var fixture = approvedFixture();
        jdbc.sql("""
                        update service_catalog
                        set execution_target_aliases = '{"primary":"other-checkout"}'::jsonb
                        where id = :serviceId
                        """)
                .param("serviceId", fixture.serviceId())
                .update();

        try {
            assertThatThrownBy(() -> executions.create(
                            fixture.incidentId(),
                            fixture.proposalId(),
                            3,
                            "execute-target-drift-" + fixture.proposalId(),
                            fixture.operator()))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            problem -> assertThat(problem.errorCode())
                                    .isEqualTo("APPROVAL_INVALIDATED"));
            assertThat(jdbc.sql("select count(*) from execution where proposal_id = :id")
                            .param("id", fixture.proposalId())
                            .query(Integer.class)
                            .single())
                    .isZero();
        } finally {
            restoreDemoTarget(fixture.serviceId());
        }
    }

    @Test
    void outboxFailureRollsBackExecutionIncidentAndIdempotencyRecord() {
        var fixture = approvedFixture();
        String idempotencyKey = "execute-rollback-" + fixture.proposalId();
        jdbc.sql("""
                        create function reject_execution_outbox_test() returns trigger
                        language plpgsql as $$
                        begin
                          raise exception 'forced execution outbox failure';
                        end;
                        $$
                        """)
                .update();
        jdbc.sql("""
                        create trigger reject_execution_outbox_test
                        before insert on outbox_event
                        for each row when (NEW.aggregate_type = 'execution')
                        execute function reject_execution_outbox_test()
                        """)
                .update();
        try {
            assertThatThrownBy(() -> executions.create(
                            fixture.incidentId(),
                            fixture.proposalId(),
                            3,
                            idempotencyKey,
                            fixture.operator()))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.sql("select count(*) from execution where proposal_id = :id")
                            .param("id", fixture.proposalId())
                            .query(Integer.class)
                            .single())
                    .isZero();
            assertThat(incidentStatus(fixture.incidentId())).isEqualTo("awaiting_approval");
            assertThat(jdbc.sql("select version from incident where id = :id")
                            .param("id", fixture.incidentId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(3);
            assertThat(jdbc.sql("select count(*) from idempotency_record where idempotency_key = :key")
                            .param("key", idempotencyKey)
                            .query(Integer.class)
                            .single())
                    .isZero();
        } finally {
            jdbc.sql("drop trigger reject_execution_outbox_test on outbox_event").update();
            jdbc.sql("drop function reject_execution_outbox_test()").update();
        }
    }

    private int executionCount(UUID executionId) {
        return jdbc.sql("select count(*) from execution where id = :id")
                .param("id", executionId)
                .query(Integer.class)
                .single();
    }

    private String incidentStatus(UUID incidentId) {
        return jdbc.sql("select status from incident where id = :id")
                .param("id", incidentId)
                .query(String.class)
                .single();
    }

    private void restoreDemoTarget(UUID serviceId) {
        jdbc.sql("""
                        update service_catalog
                        set execution_target_aliases = '{"primary":"demo-checkout"}'::jsonb
                        where id = :serviceId
                        """)
                .param("serviceId", serviceId)
                .update();
    }
}
