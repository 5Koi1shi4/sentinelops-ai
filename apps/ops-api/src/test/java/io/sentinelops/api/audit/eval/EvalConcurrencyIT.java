package io.sentinelops.api.audit.eval;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import io.sentinelops.api.diagnosis.application.model.ModelGatewayFactory;
import io.sentinelops.api.identity.application.*;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EvalConcurrencyIT extends PostgresIntegrationTest {
    @Autowired EvalApplicationService evaluations;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean ModelGatewayFactory factory;

    @Test void blockedModelHoldsNoTransactionAndDuplicateCommandDoesNotRunAgain() throws Exception {
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var active=new AtomicBoolean();
        var once=new AtomicBoolean();
        doAnswer(call->{
            var real=(ModelGatewayFactory.Session)call.callRealMethod();
            return new ModelGatewayFactory.Session(request->{
                if(once.compareAndSet(false,true)) {
                    active.set(TransactionSynchronizationManager.isActualTransactionActive());
                    entered.countDown();
                    try { if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Test latch expired"); }
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                }
                return real.gateway().diagnose(request);
            });
        }).when(factory).open(any(),any(),any());
        var key=UUID.randomUUID().toString();
        var input=new EvalApplicationService.RunRequest("incidents-v1",null);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=executor.submit(()->evaluations.run(input,key,admin()));
            try {
                assertThat(entered.await(8,TimeUnit.SECONDS)).isTrue();
                assertThat(active).isFalse();
                var repeated=executor.submit(()->catchThrowable(()->evaluations.run(input,key,admin())));
                assertThat(repeated.get(3,TimeUnit.SECONDS)).isInstanceOfSatisfying(ApiProblemException.class,
                        failure->assertThat(failure.errorCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));
                assertThat(jdbc.sql("select count(*) from eval_run where status='running'").query(Long.class).single()).isOne();
                UUID otherDataset=UUID.randomUUID(),otherCase=UUID.randomUUID();
                jdbc.sql("""
                        insert into eval_dataset(id,dataset_key,version_number,checksum,created_by_principal_id,created_at)
                        values(:id,:key,1,:hash,'0199a000-0000-7000-8000-000000000002',clock_timestamp())
                        """).param("id",otherDataset).param("key","other-"+otherDataset).param("hash",otherDataset.toString()).update();
                jdbc.sql("""
                        insert into eval_case(id,dataset_id,case_key,input_fixture,expectation,tags,checksum)
                        values(:id,:dataset,'other','{}','{}','{}',:hash)
                        """).param("id",otherCase).param("dataset",otherDataset).param("hash",otherCase.toString()).update();
                assertThatThrownBy(()->jdbc.sql("""
                        insert into eval_case_result(id,eval_run_id,eval_case_id,dataset_id,owner_token,status,scores,latency_ms,created_at)
                        select :id,id,:case,dataset_id,owner_token,'passed','{}',0,clock_timestamp() from eval_run where status='running'
                        """).param("id",UUID.randomUUID()).param("case",otherCase).update())
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            } finally { release.countDown(); }
            assertThat(pending.get(10,TimeUnit.SECONDS).results()).hasSize(12);
        }
    }

    @Test void expiredOwnerCannotAppendResultsAfterRecoveryMarksRunFailed() throws Exception {
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        doAnswer(call->{
            var real=(ModelGatewayFactory.Session)call.callRealMethod();
            return new ModelGatewayFactory.Session(request->{
                entered.countDown();
                try { if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Test latch expired"); }
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return real.gateway().diagnose(request);
            });
        }).when(factory).open(any(),any(),any());
        var key=UUID.randomUUID().toString();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=executor.submit(()->evaluations.run(new EvalApplicationService.RunRequest("incidents-v1",null),key,admin()));
            try {
                assertThat(entered.await(8,TimeUnit.SECONDS)).isTrue();
                var id=jdbc.sql("select r.id from eval_run r join idempotency_record c on c.id=r.command_id where c.idempotency_key=:key")
                        .param("key",key).query(UUID.class).single();
                jdbc.sql("update eval_run set lease_expires_at=clock_timestamp()-interval '1 second' where id=:id").param("id",id).update();
                assertThat(evaluations.get(id,admin()).status()).isEqualTo("failed");
            } finally { release.countDown(); }
            var late=pending.get(10,TimeUnit.SECONDS);
            assertThat(late.status()).isEqualTo("failed");
            assertThat(late.releaseAllowed()).isFalse();
            assertThat(late.results()).isEmpty();
            assertThatThrownBy(()->evaluations.run(new EvalApplicationService.RunRequest("incidents-v1",late.id()),UUID.randomUUID().toString(),admin()))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            failure->assertThat(failure.errorCode()).isEqualTo("EVAL_BASELINE_INCOMPATIBLE"));
        }
    }

    @Test void saturatedCapacityPreservesCompletedReplayAndRunningConflict() throws Exception {
        var request=new EvalApplicationService.RunRequest("incidents-v1",null);
        var completedKey=UUID.randomUUID().toString();
        var completed=evaluations.run(request,completedKey,admin());
        var entered=new CountDownLatch(2);
        var release=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{
            var real=(ModelGatewayFactory.Session)call.callRealMethod();
            return new ModelGatewayFactory.Session(input->{
                if(calls.getAndIncrement()<2) {
                    entered.countDown();
                    try { if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Test latch expired"); }
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                }
                return real.gateway().diagnose(input);
            });
        }).when(factory).open(any(),any(),any());
        var firstKey=UUID.randomUUID().toString();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->evaluations.run(request,firstKey,admin()));
            var second=executor.submit(()->evaluations.run(request,UUID.randomUUID().toString(),admin()));
            try {
                assertThat(entered.await(8,TimeUnit.SECONDS)).isTrue();
                assertThat(evaluations.run(request,completedKey,admin()).id()).isEqualTo(completed.id());
                assertThatThrownBy(()->evaluations.run(request,firstKey,admin()))
                        .isInstanceOfSatisfying(ApiProblemException.class,
                                failure->assertThat(failure.errorCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));
                var rejectedKey=UUID.randomUUID().toString();
                assertThatThrownBy(()->evaluations.run(request,rejectedKey,admin()))
                        .isInstanceOfSatisfying(ApiProblemException.class,
                                failure->assertThat(failure.errorCode()).isEqualTo("EVAL_CAPACITY_EXCEEDED"));
                assertThat(jdbc.sql("select count(*) from idempotency_record where idempotency_key=:key")
                        .param("key",rejectedKey).query(Long.class).single()).isZero();
            } finally { release.countDown(); }
            assertThat(first.get(10,TimeUnit.SECONDS).status()).isEqualTo("completed");
            assertThat(second.get(10,TimeUnit.SECONDS).status()).isEqualTo("completed");
        }
    }
    private CurrentPrincipal admin() {
        return new CurrentPrincipal("https://issuer.sentinelops.test","eval-concurrency",Set.of(PlatformRole.PLATFORM_ADMIN),Set.of());
    }
}
