package io.sentinelops.api.diagnosis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.sentinelops.api.diagnosis.application.DiagnosisApplicationService;
import io.sentinelops.api.diagnosis.application.DiagnosisEngine;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PrincipalLookup;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class DiagnosisClaimIT extends PostgresIntegrationTest {
    @Autowired DiagnosisApplicationService diagnoses;
    @Autowired IncidentApplicationService incidents;
    @Autowired PrincipalLookup principals;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean io.sentinelops.api.diagnosis.application.model.ModelGateway engine;
    @MockitoSpyBean io.sentinelops.api.diagnosis.application.DiagnosisPolicy policy;
    private static final UUID SERVICE = UUID.fromString("0199a000-0000-7000-8000-000000000001");

    @Test
    void providerRunsOutsideTransaction() {
        var active = new AtomicBoolean();
        doAnswer(call -> {
            active.set(TransactionSynchronizationManager.isActualTransactionActive());
            return call.callRealMethod();
        }).when(engine).diagnose(any());
        var principal = operator();
        diagnoses.diagnose(incident(), 0, UUID.randomUUID().toString(), principal,
                principals.upsert(principal, "claim operator"));
        assertThat(active).isFalse();
    }

    @Test
    void blockedProviderDoesNotHoldIncidentLockAndDuplicateReturnsImmediately() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return call.callRealMethod();
        }).when(engine).diagnose(any());
        var id = incident();
        var principal = operator();
        var principalId = principals.upsert(principal, "claim operator");
        var key = UUID.randomUUID().toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> diagnoses.diagnose(id, 0, key, principal, principalId));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var duplicate = executor.submit(() -> catchThrowable(() ->
                        diagnoses.diagnose(id, 0, key, principal, principalId)));
                assertThat(duplicate.get(2, TimeUnit.SECONDS)).isInstanceOfSatisfying(
                        ApiProblemException.class, failure -> assertThat(failure.errorCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));
                var unlocked = executor.submit(() -> jdbc.sql("select id from incident where id=:id for update nowait")
                        .param("id", id).query(UUID.class).single());
                assertThat(unlocked.get(2, TimeUnit.SECONDS)).isEqualTo(id);
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS).incidentId()).isEqualTo(id);
        }
    }

    @Test
    void providerFailureIsTerminalAndRetryWithNewCommandRecovers() {
        doAnswer(call -> { throw new IllegalStateException("secret provider body"); }).when(engine).diagnose(any());
        var id = incident();
        var principal = operator();
        var principalId = principals.upsert(principal, "claim operator");
        var key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> diagnoses.diagnose(id, 0, key, principal, principalId))
                .isInstanceOf(ApiProblemException.class).hasMessageNotContaining("secret provider body");
        assertThat(jdbc.sql("select status from diagnosis_run where incident_id=:id")
                .param("id", id).query(String.class).single()).isEqualTo("failed");
        doAnswer(call -> call.callRealMethod()).when(engine).diagnose(any());
        assertThat(diagnoses.diagnose(id, 1, UUID.randomUUID().toString(), principal, principalId).incidentId()).isEqualTo(id);
    }

    @Test
    void manualOnlyRejectsDiagnosisAndLeavesIncidentAvailableForManualTriage() {
        doAnswer(call -> new io.sentinelops.api.diagnosis.adapter.out.model.UnavailableModelGateway()
                .diagnose(call.getArgument(0))).when(engine).diagnose(any());
        var id = incident();
        var principal = operator();
        var principalId = principals.upsert(principal, "claim operator");
        assertThatThrownBy(() -> diagnoses.diagnose(id, 0, UUID.randomUUID().toString(), principal, principalId))
                .isInstanceOfSatisfying(ApiProblemException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
                    assertThat(failure.status().value()).isEqualTo(503);
                });
        assertThat(incidents.get(id).id()).isEqualTo(id);
        assertThat(jdbc.sql("select status from incident where id=:id").param("id", id)
                .query(String.class).single()).isEqualTo("triaging");
        assertThat(jdbc.sql("select count(*) from diagnosis_proposal where incident_id=:id")
                .param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void replayRechecksCurrentServiceAuthorization() {
        var id = incident();
        var principal = operator();
        var principalId = principals.upsert(principal, "claim operator");
        var key = UUID.randomUUID().toString();
        diagnoses.diagnose(id, 0, key, principal, principalId);
        var revoked = new CurrentPrincipal(principal.issuer(), principal.subject(), principal.roles(), Set.of());
        assertThatThrownBy(() -> diagnoses.diagnose(id, 0, key, revoked, principalId))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.status().value()).isEqualTo(403));
    }

    @Test
    void expiredOwnerCannotPublishAfterReplacementCompletes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstCall = new AtomicBoolean(true);
        doAnswer(call -> {
            if (firstCall.getAndSet(false)) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            }
            return call.callRealMethod();
        }).when(engine).diagnose(any());
        var id = incident();
        var principal = operator();
        var principalId = principals.upsert(principal, "claim operator");
        var key = UUID.randomUUID().toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(() -> catchThrowable(() -> diagnoses.diagnose(id, 0, key, principal, principalId)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                jdbc.sql("""
                        update diagnosis_run set started_at=clock_timestamp()-interval '100 seconds',
                          lease_expires_at=clock_timestamp()-interval '11 seconds' where incident_id=:id
                        """).param("id", id).update();
                var replacement = diagnoses.diagnose(id, 1, UUID.randomUUID().toString(), principal, principalId);
                assertThat(replacement.incidentId()).isEqualTo(id);
            } finally { release.countDown(); }
            assertThat(old.get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(ApiProblemException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo("MODEL_TIMEOUT"));
        }
        assertThat(jdbc.sql("select count(*) from diagnosis_proposal where incident_id=:id")
                .param("id", id).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("select status from diagnosis_run where incident_id=:id order by started_at")
                .param("id", id).query(String.class).list()).containsExactly("failed", "succeeded");
    }

    @Test
    void leaseExpiringDuringFinalValidationCannotPublishProposal() {
        var id = incident();
        doAnswer(call -> {
            jdbc.sql("""
                    update diagnosis_run set started_at=clock_timestamp()-interval '100 seconds',
                      lease_expires_at=clock_timestamp()-interval '11 seconds' where incident_id=:id
                    """).param("id", id).update();
            return call.callRealMethod();
        }).when(policy).validate(any(), any(), any());
        var principal = operator();
        var principalId = principals.upsert(principal, "claim operator");
        assertThatThrownBy(() -> diagnoses.diagnose(id, 0, UUID.randomUUID().toString(), principal, principalId))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo("MODEL_TIMEOUT"));
        assertThat(jdbc.sql("select count(*) from diagnosis_proposal where incident_id=:id")
                .param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void changedIncidentVersionRejectsProviderResult() {
        doAnswer(call -> {
            var context = ((io.sentinelops.api.diagnosis.application.model.ModelDiagnosisRequest) call.getArgument(0)).context();
            jdbc.sql("update incident set version=version+1 where id=:id").param("id", context.incidentId()).update();
            return call.callRealMethod();
        }).when(engine).diagnose(any());
        var id = incident();
        var principal = operator();
        var principalId = principals.upsert(principal, "claim operator");
        assertThatThrownBy(() -> diagnoses.diagnose(id, 0, UUID.randomUUID().toString(), principal, principalId))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo("DIAGNOSIS_STALE"));
        assertThat(jdbc.sql("select count(*) from diagnosis_proposal where incident_id=:id")
                .param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void databaseRejectsSecondActiveRunEvenWhenBypassingApplication() {
        doAnswer(call -> {
            var context = ((io.sentinelops.api.diagnosis.application.model.ModelDiagnosisRequest) call.getArgument(0)).context();
            assertThatThrownBy(() -> jdbc.sql("""
                    insert into diagnosis_run(id,incident_id,requested_by_principal_id,incident_version,
                      engine_type,status,prompt_version,input_hash,started_at,owner_token,lease_expires_at)
                    select :newId,incident_id,requested_by_principal_id,incident_version,
                      engine_type,'running',prompt_version,input_hash,clock_timestamp(),:newId,
                      clock_timestamp()+interval '89 seconds' from diagnosis_run where incident_id=:incident
                    """).param("newId", UUID.randomUUID()).param("incident", context.incidentId()).update())
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            return call.callRealMethod();
        }).when(engine).diagnose(any());
        var id = incident();
        var principal = operator();
        var principalId = principals.upsert(principal, "claim operator");
        assertThat(diagnoses.diagnose(id, 0, UUID.randomUUID().toString(), principal, principalId).incidentId()).isEqualTo(id);
    }

    private CurrentPrincipal operator() {
        return new CurrentPrincipal("https://issuer.sentinelops.test", "claim-operator",
                Set.of(PlatformRole.ON_CALL_OPERATOR), Set.of(SERVICE));
    }

    private UUID incident() {
        var key = UUID.randomUUID().toString();
        return incidents.ingest(new AlertEnvelope("alertmanager", key, "checkout-api", key,
                "Claim lifecycle test", "sev2", AlertEnvelope.AlertStatus.FIRING,
                mapper.createObjectNode().put("status", "firing"))).id();
    }
}
