package io.sentinelops.api.approval;

import static io.sentinelops.api.approval.domain.ApprovalDecision.APPROVE;
import static io.sentinelops.api.approval.domain.ApprovalDecision.REJECT;
import static io.sentinelops.api.approval.domain.ApprovalStatus.APPROVED;
import static io.sentinelops.api.approval.domain.ApprovalStatus.PENDING;
import static io.sentinelops.api.approval.domain.ApprovalStatus.REJECTED;
import static io.sentinelops.api.identity.application.PlatformRole.ON_CALL_OPERATOR;
import static io.sentinelops.api.identity.application.PlatformRole.SRE_APPROVER;
import static io.sentinelops.api.incident.domain.IncidentStatus.ESCALATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.api.approval.application.ApprovalApplicationService;
import io.sentinelops.api.approval.application.ApprovalApplicationService.DecisionCommand;
import io.sentinelops.api.approval.application.ApprovalApplicationService.RequestContext;
import io.sentinelops.api.approval.domain.ApprovalStatus;
import io.sentinelops.api.approval.domain.SeparationOfDutiesException;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ApprovalConcurrencyIT extends PostgresIntegrationTest {

    @Autowired private ApprovalApplicationService approvals;
    @Autowired private JdbcClient jdbc;

    @Test
    void twoConcurrentR2ApproversPersistOnceAndGrantQuorumOnce() throws Exception {
        var fixture = fixture(RiskLevel.R2);
        var created = approvals.request(
                fixture.incidentId(),
                fixture.proposalId(),
                2,
                "request-r2-" + fixture.incidentId(),
                fixture.requester());
        assertThat(created.status()).isEqualTo(PENDING);

        var firstApprover = context("approver-a-" + fixture.incidentId(), fixture.serviceId());
        var secondApprover = context("approver-b-" + fixture.incidentId(), fixture.serviceId());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return approvals.decide(
                        created.id(),
                        created.incidentVersion(),
                        "decision-a-" + created.id(),
                        new DecisionCommand(APPROVE, "first approval", fixture.proposalHash()),
                        firstApprover);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return approvals.decide(
                        created.id(),
                        created.incidentVersion(),
                        "decision-b-" + created.id(),
                        new DecisionCommand(APPROVE, "second approval", fixture.proposalHash()),
                        secondApprover);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(Set.of(first.get(10, TimeUnit.SECONDS).status(),
                            second.get(10, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder(PENDING, APPROVED);
        }

        assertThat(decisionCount(created.id())).isEqualTo(2);
        assertThat(requestStatus(created.id())).isEqualTo(APPROVED);
        assertThat(requestVersion(created.id())).isEqualTo(2);
        assertThat(grantedEventCount(fixture.incidentId())).isOne();
    }

    @Test
    void requesterCannotApproveOwnR1Request() {
        var fixture = fixture(RiskLevel.R1, true);
        var created = approvals.request(
                fixture.incidentId(),
                fixture.proposalId(),
                2,
                "request-r1-" + fixture.incidentId(),
                fixture.requester());

        assertThatThrownBy(() -> approvals.decide(
                        created.id(),
                        created.incidentVersion(),
                        "self-decision-" + created.id(),
                        new DecisionCommand(APPROVE, "self approval", fixture.proposalHash()),
                        fixture.requester()))
                .isInstanceOf(SeparationOfDutiesException.class);
        assertThat(decisionCount(created.id())).isZero();
        assertThat(requestStatus(created.id())).isEqualTo(PENDING);
    }

    @Test
    void expiredRequestIsPersistentlyInvalidatedUsingDatabaseTime() {
        var fixture = fixture(RiskLevel.R1);
        var created = approvals.request(
                fixture.incidentId(),
                fixture.proposalId(),
                2,
                "request-expired-" + fixture.incidentId(),
                fixture.requester());
        jdbc.sql("""
                        update approval_request
                        set created_at = clock_timestamp() - interval '10 minutes',
                            expires_at = clock_timestamp() - interval '1 second'
                        where id = :id
                        """)
                .param("id", created.id())
                .update();

        assertThatThrownBy(() -> approvals.decide(
                        created.id(),
                        created.incidentVersion(),
                        "expired-decision-" + created.id(),
                        new DecisionCommand(APPROVE, "too late", fixture.proposalHash()),
                        context("late-approver-" + created.id(), fixture.serviceId())))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo("APPROVAL_INVALIDATED"));
        assertThat(requestStatus(created.id())).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(decisionCount(created.id())).isZero();
        assertThat(incidentStatus(fixture.incidentId())).isEqualTo(ESCALATED);
        assertThat(incidentVersion(fixture.incidentId())).isEqualTo(4);
    }

    @Test
    void changedProposalHashInvalidatesBeforeDecisionPersistence() {
        var fixture = fixture(RiskLevel.R1);
        var created = approvals.request(
                fixture.incidentId(),
                fixture.proposalId(),
                2,
                "request-hash-" + fixture.incidentId(),
                fixture.requester());
        jdbc.sql("update approval_request set proposal_hash = 'stale-hash' where id = :id")
                .param("id", created.id())
                .update();

        assertThatThrownBy(() -> approvals.decide(
                        created.id(),
                        created.incidentVersion(),
                        "hash-decision-" + created.id(),
                        new DecisionCommand(APPROVE, "stale", fixture.proposalHash()),
                        context("hash-approver-" + created.id(), fixture.serviceId())))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo("APPROVAL_INVALIDATED"));
        assertThat(requestStatus(created.id())).isEqualTo(ApprovalStatus.INVALIDATED);
        assertThat(decisionCount(created.id())).isZero();
        assertThat(incidentStatus(fixture.incidentId())).isEqualTo(ESCALATED);
        assertThat(incidentVersion(fixture.incidentId())).isEqualTo(4);
    }

    @Test
    void callerSuppliedHashMismatchHasNoSideEffects() {
        var fixture = fixture(RiskLevel.R1);
        var created = approvals.request(
                fixture.incidentId(),
                fixture.proposalId(),
                2,
                "request-client-hash-" + fixture.incidentId(),
                fixture.requester());

        assertThatThrownBy(() -> approvals.decide(
                        created.id(),
                        created.incidentVersion(),
                        "client-hash-decision-" + created.id(),
                        new DecisionCommand(APPROVE, "wrong hash", "caller-controlled-hash"),
                        context("hash-client-approver-" + created.id(), fixture.serviceId())))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo("proposal_hash_mismatch"));
        assertThat(requestStatus(created.id())).isEqualTo(PENDING);
        assertThat(decisionCount(created.id())).isZero();
        assertThat(incidentStatus(fixture.incidentId()))
                .isEqualTo(io.sentinelops.api.incident.domain.IncidentStatus.AWAITING_APPROVAL);
        assertThat(incidentVersion(fixture.incidentId())).isEqualTo(3);
    }

    @Test
    void rejectionEscalatesIncidentAndReturnsTheNewIncidentVersion() {
        var fixture = fixture(RiskLevel.R1);
        var created = approvals.request(
                fixture.incidentId(),
                fixture.proposalId(),
                2,
                "request-reject-" + fixture.incidentId(),
                fixture.requester());

        var rejected = approvals.decide(
                created.id(),
                created.incidentVersion(),
                "reject-decision-" + created.id(),
                new DecisionCommand(REJECT, "unsafe", fixture.proposalHash()),
                context("rejecting-approver-" + created.id(), fixture.serviceId()));

        assertThat(rejected.status()).isEqualTo(REJECTED);
        assertThat(rejected.incidentVersion()).isEqualTo(4);
        assertThat(incidentStatus(fixture.incidentId())).isEqualTo(ESCALATED);
        assertThat(incidentVersion(fixture.incidentId())).isEqualTo(4);
    }

    @Test
    void requestAndDecisionReplayWithoutDuplicateRowsOrEvents() {
        var fixture = fixture(RiskLevel.R1);
        String requestKey = "request-replay-" + fixture.incidentId();
        var firstRequest = approvals.request(
                fixture.incidentId(),
                fixture.proposalId(),
                2,
                requestKey,
                fixture.requester());
        var replayedRequest = approvals.request(
                fixture.incidentId(),
                fixture.proposalId(),
                2,
                requestKey,
                fixture.requester());

        assertThat(replayedRequest).isEqualTo(firstRequest);
        assertThat(requestCount(fixture.proposalId())).isOne();
        assertThat(eventCount(fixture.incidentId(), "approval_requested")).isOne();

        var approver = context("replay-approver-" + firstRequest.id(), fixture.serviceId());
        var command = new DecisionCommand(APPROVE, "approved once", fixture.proposalHash());
        String decisionKey = "decision-replay-" + firstRequest.id();
        var firstDecision = approvals.decide(
                firstRequest.id(),
                firstRequest.incidentVersion(),
                decisionKey,
                command,
                approver);
        var replayedDecision = approvals.decide(
                firstRequest.id(),
                firstRequest.incidentVersion(),
                decisionKey,
                command,
                approver);

        assertThat(replayedDecision).isEqualTo(firstDecision);
        assertThat(decisionCount(firstRequest.id())).isOne();
        assertThat(eventCount(fixture.incidentId(), "approval_granted")).isOne();
    }

    private Fixture fixture(RiskLevel risk) {
        return fixture(risk, false);
    }

    private Fixture fixture(RiskLevel risk, boolean requesterCanApprove) {
        UUID serviceId = jdbc.sql("select id from service_catalog where service_key = 'checkout-api'")
                .query(UUID.class)
                .single();
        String suffix = UUID.randomUUID().toString();
        var requesterRoles = requesterCanApprove
                ? Set.of(ON_CALL_OPERATOR, SRE_APPROVER)
                : Set.of(ON_CALL_OPERATOR);
        var requester = context("requester-" + suffix, serviceId, requesterRoles);
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        String proposalHash = "proposal-hash-" + suffix;
        var now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.sql("""
                        insert into incident(
                          id, service_id, fingerprint, title, severity, status,
                          version, next_event_seq, occurrence_count, opened_at, updated_at
                        ) values (
                          :id, :serviceId, :fingerprint, 'Approval fixture', 'sev2', 'diagnosed',
                          2, 1, 1, :now, :now
                        )
                        """)
                .param("id", incidentId)
                .param("serviceId", serviceId)
                .param("fingerprint", "approval-" + suffix)
                .param("now", now)
                .update();
        jdbc.sql("""
                        insert into diagnosis_run(
                          id, incident_id, requested_by_principal_id, incident_version,
                          engine_type, status, prompt_version, input_hash, started_at, completed_at
                        ) values (
                          :id, :incidentId, :principalId, 0,
                          'deterministic', 'succeeded', 'fixture-v1', :inputHash, :now, :now
                        )
                        """)
                .param("id", runId)
                .param("incidentId", incidentId)
                .param("principalId", requester.principalId())
                .param("inputHash", "input-" + suffix)
                .param("now", now)
                .update();
        jdbc.sql("""
                        insert into diagnosis_proposal(
                          id, diagnosis_run_id, incident_id, summary, proposal_payload,
                          proposal_hash, risk_level, created_at
                        ) values (
                          :id, :runId, :incidentId, 'Approval fixture', '{}'::jsonb,
                          :proposalHash, :risk, :now
                        )
                        """)
                .param("id", proposalId)
                .param("runId", runId)
                .param("incidentId", incidentId)
                .param("proposalHash", proposalHash)
                .param("risk", risk.databaseValue())
                .param("now", now)
                .update();
        return new Fixture(
                incidentId, proposalId, proposalHash, serviceId, requester);
    }

    private RequestContext context(String subject, UUID serviceId) {
        return context(subject, serviceId, Set.of(SRE_APPROVER));
    }

    private RequestContext context(
            String subject, UUID serviceId, Set<io.sentinelops.api.identity.application.PlatformRole> roles) {
        var principal = new CurrentPrincipal(
                "https://issuer.sentinelops.test", subject, roles, Set.of(serviceId));
        UUID principalId = UUID.randomUUID();
        jdbc.sql("""
                        insert into principal(id, issuer, subject, display_name, created_at)
                        values (:id, :issuer, :subject, :subject, clock_timestamp())
                        """)
                .param("id", principalId)
                .param("issuer", principal.issuer())
                .param("subject", subject)
                .update();
        return new RequestContext(principal, principalId);
    }

    private int decisionCount(UUID requestId) {
        return jdbc.sql("select count(*) from approval_decision where approval_request_id = :id")
                .param("id", requestId)
                .query(Integer.class)
                .single();
    }

    private ApprovalStatus requestStatus(UUID requestId) {
        return jdbc.sql("select status from approval_request where id = :id")
                .param("id", requestId)
                .query((resultSet, rowNumber) ->
                        ApprovalStatus.fromDatabase(resultSet.getString("status")))
                .single();
    }

    private long requestVersion(UUID requestId) {
        return jdbc.sql("select resource_version from approval_request where id = :id")
                .param("id", requestId)
                .query(Long.class)
                .single();
    }

    private io.sentinelops.api.incident.domain.IncidentStatus incidentStatus(UUID incidentId) {
        return jdbc.sql("select status from incident where id = :id")
                .param("id", incidentId)
                .query((resultSet, rowNumber) ->
                        io.sentinelops.api.incident.domain.IncidentStatus.fromDatabase(
                                resultSet.getString("status")))
                .single();
    }

    private long incidentVersion(UUID incidentId) {
        return jdbc.sql("select version from incident where id = :id")
                .param("id", incidentId)
                .query(Long.class)
                .single();
    }

    private int grantedEventCount(UUID incidentId) {
        return eventCount(incidentId, "approval_granted");
    }

    private int requestCount(UUID proposalId) {
        return jdbc.sql("select count(*) from approval_request where proposal_id = :proposalId")
                .param("proposalId", proposalId)
                .query(Integer.class)
                .single();
    }

    private int eventCount(UUID incidentId, String eventType) {
        return jdbc.sql("""
                        select count(*) from incident_event
                        where incident_id = :incidentId and event_type = :eventType
                        """)
                .param("incidentId", incidentId)
                .param("eventType", eventType)
                .query(Integer.class)
                .single();
    }

    private record Fixture(
            UUID incidentId,
            UUID proposalId,
            String proposalHash,
            UUID serviceId,
            RequestContext requester) {}
}
