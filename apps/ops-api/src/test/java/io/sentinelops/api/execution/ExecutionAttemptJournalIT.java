package io.sentinelops.api.execution;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.EXECUTOR_AUTHORITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sentinelops.api.execution.application.ExecutionApplicationService.CompletionCommand;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

class ExecutionAttemptJournalIT extends ExecutionFixtureSupport {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity()).build();
    }

    @Test
    void executorRecordsPreparedThenDispatchedUnderTheCurrentLease() throws Exception {
        var fixture = approvedFixture();
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "attempt-journal-" + fixture.proposalId(), fixture.operator());
        var claim = executions.claim(execution.id(), "journal-executor");

        recordPhase(execution.id(), claim.ticket(), claim.fencingToken(),
                "prepared", "journal-prepared-" + execution.id())
                .andExpect(status().isOk());
        recordPhase(execution.id(), claim.ticket(), claim.fencingToken(),
                "dispatched", "journal-dispatched-" + execution.id())
                .andExpect(status().isOk());

        assertThat(jdbc.sql("""
                select phase from execution_attempt_event
                where execution_id = :id order by occurred_at, id
                """).param("id", execution.id()).query(String.class).list())
                .containsExactly("prepared", "dispatched");
        assertThatThrownBy(() -> jdbc.sql("""
                update execution_attempt_event set phase = 'acknowledged'
                where execution_id = :id and phase = 'dispatched'
                """).param("id", execution.id()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void staleOwnerCannotAddAFalseDispatchRecord() throws Exception {
        var fixture = approvedFixture();
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "stale-journal-" + fixture.proposalId(), fixture.operator());
        var first = executions.claim(execution.id(), "journal-executor");
        jdbc.sql("update execution set lease_until = clock_timestamp() - interval '1 second' where id = :id")
                .param("id", execution.id()).update();
        executions.claim(execution.id(), "other-executor");

        recordPhase(execution.id(), first.ticket(), first.fencingToken(),
                "prepared", "stale-journal-phase-" + execution.id())
                .andExpect(status().isConflict());
        assertThat(jdbc.sql("select count(*) from execution_attempt_event where execution_id = :id")
                .param("id", execution.id()).query(Integer.class).single())
                .isZero();
    }

    @Test
    void completedDispatchAddsAnAcknowledgedTerminalPhase() throws Exception {
        var fixture = approvedFixture();
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "terminal-journal-" + fixture.proposalId(), fixture.operator());
        var claim = executions.claim(execution.id(), "journal-executor");
        recordPhase(execution.id(), claim.ticket(), claim.fencingToken(),
                "prepared", "terminal-prepared-" + execution.id())
                .andExpect(status().isOk());
        recordPhase(execution.id(), claim.ticket(), claim.fencingToken(),
                "dispatched", "terminal-dispatched-" + execution.id())
                .andExpect(status().isOk());

        executions.complete(execution.id(), "journal-executor", claim.fencingToken(),
                claim.ticket(), report(claim.fencingToken()));

        assertThat(phases(execution.id()))
                .containsExactly("prepared", "dispatched", "acknowledged");
    }

    @Test
    void locallyRejectedStepAddsFailedBeforeDispatchPhase() throws Exception {
        var fixture = approvedFixture();
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "local-reject-journal-" + fixture.proposalId(), fixture.operator());
        var claim = executions.claim(execution.id(), "journal-executor");
        recordPhase(execution.id(), claim.ticket(), claim.fencingToken(),
                "prepared", "local-reject-prepared-" + execution.id())
                .andExpect(status().isOk());

        executions.fail(execution.id(), "journal-executor", claim.fencingToken(),
                claim.ticket(), report(claim.fencingToken()));

        assertThat(phases(execution.id()))
                .containsExactly("prepared", "failed_before_dispatch");
    }

    @Test
    void executorHttpResultWithoutPhaseEvidenceIsRejected() {
        var fixture = approvedFixture();
        var execution = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                "unproven-result-" + fixture.proposalId(), fixture.operator());
        var claim = executions.claim(execution.id(), "journal-executor");

        assertThatThrownBy(() -> executions.complete(
                execution.id(), "journal-executor", claim.fencingToken(),
                claim.ticket(), report(claim.fencingToken()),
                "https://issuer.sentinelops.test\u001fjournal-executor",
                "unproven-result-" + execution.id()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.errorCode())
                                .isEqualTo("execution_attempt_phase_missing"));
        assertThat(jdbc.sql("select count(*) from execution_attempt where execution_id = :id")
                .param("id", execution.id()).query(Integer.class).single())
                .isZero();
    }

    private CompletionCommand report(long fence) {
        return new CompletionCommand("recover-one", (int) fence, "demo-http",
                "demo-http-v1", "request-hash", Map.of("changed", true));
    }

    private List<String> phases(UUID executionId) {
        return jdbc.sql("""
                select phase from execution_attempt_event
                where execution_id = :id order by occurred_at, id
                """).param("id", executionId).query(String.class).list();
    }

    private org.springframework.test.web.servlet.ResultActions recordPhase(UUID executionId,
            String ticket, long fence, String phase, String key) throws Exception {
        return mockMvc.perform(post("/internal/v1/executions/{id}:attempt-events", executionId)
                .with(jwt().authorities(new SimpleGrantedAuthority(EXECUTOR_AUTHORITY))
                        .jwt(token -> token.issuer("https://issuer.sentinelops.test")
                                .subject("journal-executor")
                                .audience(List.of("sentinelops-executor"))
                                .claim("realm_access", Map.of("roles", List.of("sentinelops_executor")))))
                .header("Idempotency-Key", key)
                .header("X-SentinelOps-Execution-Ticket", ticket)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "fencingToken", fence,
                        "stepId", "recover-one",
                        "attemptNo", (int) fence,
                        "phase", phase,
                        "metadata", Map.of()))));
    }
}
