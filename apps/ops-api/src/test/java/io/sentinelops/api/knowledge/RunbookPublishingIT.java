package io.sentinelops.api.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.knowledge.application.*;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class RunbookPublishingIT extends PostgresIntegrationTest {
    static final UUID SERVICE = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    @Autowired RunbookApplicationService runbooks;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean EmbeddingGateway embeddings;
    final CurrentPrincipal author = principal("writer");
    final CurrentPrincipal reviewer = principal("reviewer");

    @BeforeEach void embeddingProvider() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new DeterministicEmbeddingGateway().embed(call.getArgument(0));
        }).when(embeddings).embed(anyList());
    }

    @Test void publishFreezesReviewedContentAndReplaysWithoutAnotherProviderCall() {
        var draft = create();
        var reviewed = runbooks.review(draft.id(), draft.revision(), key(), reviewer);
        String command = key();
        var published = runbooks.publish(draft.id(), reviewed.revision(), command, author);
        assertThat(published.lifecycle()).isEqualTo("published");
        assertThat(published.publishedAt()).isNotNull();
        assertThat(chunks(draft.id())).isPositive();
        assertThat(runbooks.publish(draft.id(), reviewed.revision(), command, author)).isEqualTo(published);
        verify(embeddings, times(1)).embed(anyList());
        assertThat(jdbc.sql("select count(*) from audit_record where resource_id=:id and action='runbook_published'")
                .param("id", draft.id().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test void publishedVersionCannotBeUpdatedOrDeleted() {
        var published = publish(create());
        assertThatThrownBy(() -> jdbc.sql("update runbook_version set definition='{}' where id=:id")
                .param("id", published.id()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from runbook_version where id=:id")
                .param("id", published.id()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("update knowledge_chunk set content='changed' where runbook_version_id=:id")
                .param("id", published.id()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from knowledge_chunk where runbook_version_id=:id")
                .param("id", published.id()).update()).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.sql("update runbook_version set lifecycle='retired' where id=:id")
                .param("id", published.id()).update()).isEqualTo(1);
    }

    @Test void authorCannotReviewAndEditingInvalidatesReview() {
        var draft = create();
        assertThatThrownBy(() -> runbooks.review(draft.id(), draft.revision(), key(), author))
                .isInstanceOf(ApiProblemException.class);
        var reviewed = runbooks.review(draft.id(), draft.revision(), key(), reviewer);
        var edited = runbooks.updateDraft(draft.id(), reviewed.revision(),
                new RunbookApplicationService.DraftContent(draft.definition(), "# Revised\n\nconnection pool timeout"), key(), author);
        assertThat(edited.reviewerPrincipalId()).isNull();
        assertThatThrownBy(() -> runbooks.publish(edited.id(), edited.revision(), key(), author))
                .isInstanceOf(ApiProblemException.class);
        assertThat(chunks(draft.id())).isZero();
    }

    @Test void lastEditorCannotReviewEvenIfNotOriginalAuthor() {
        var draft = create();
        var edited = runbooks.updateDraft(draft.id(), draft.revision(),
                new RunbookApplicationService.DraftContent(draft.definition(), "Changed documentation"), key(), reviewer);
        assertThatThrownBy(() -> runbooks.review(edited.id(), edited.revision(), key(), reviewer))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test void staleDraftCannotPublishAfterProviderCompletes() {
        var draft = create();
        var reviewed = runbooks.review(draft.id(), draft.revision(), key(), reviewer);
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            runbooks.updateDraft(draft.id(), reviewed.revision(),
                    new RunbookApplicationService.DraftContent(draft.definition(), "New content"), key(), author);
            return new DeterministicEmbeddingGateway().embed(call.getArgument(0));
        });
        assertThatThrownBy(() -> runbooks.publish(draft.id(), reviewed.revision(), key(), author))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(runbooks.get(draft.id(), author).lifecycle()).isEqualTo("draft");
        assertThat(chunks(draft.id())).isZero();
    }

    @Test void malformedVectorsFailWithoutPartialPublication() {
        for (float[] vector : List.of(new float[12], new float[1536], invalidVector())) {
            var draft = create();
            var reviewed = runbooks.review(draft.id(), draft.revision(), key(), reviewer);
            when(embeddings.embed(anyList())).thenReturn(List.of(vector));
            assertThatThrownBy(() -> runbooks.publish(draft.id(), reviewed.revision(), key(), author))
                    .isInstanceOf(ApiProblemException.class);
            assertThat(runbooks.get(draft.id(), author).lifecycle()).isEqualTo("draft");
            assertThat(chunks(draft.id())).isZero();
        }
    }

    @Test void providerFailureLeavesReviewedDraftAndNoCommandToReplay() {
        var draft = create();
        var reviewed = runbooks.review(draft.id(), draft.revision(), key(), reviewer);
        when(embeddings.embed(anyList())).thenThrow(new IllegalStateException("provider private detail"));
        String command = key();
        assertThatThrownBy(() -> runbooks.publish(draft.id(), reviewed.revision(), command, author))
                .isInstanceOf(ApiProblemException.class).hasMessageNotContaining("private detail");
        assertThat(chunks(draft.id())).isZero();
        embeddingProvider();
        assertThat(runbooks.publish(draft.id(), reviewed.revision(), command, author).lifecycle()).isEqualTo("published");
    }

    @Test void staleEditsAndIdempotencyKeyReuseCannotOverwriteContent() {
        var draft = create();
        String command = key();
        var body = new RunbookApplicationService.DraftContent(draft.definition(), "Edited documentation");
        var edited = runbooks.updateDraft(draft.id(), draft.revision(), body, command, author);
        assertThat(runbooks.updateDraft(draft.id(), draft.revision(), body, command, author)).isEqualTo(edited);
        assertThatThrownBy(() -> runbooks.updateDraft(draft.id(), draft.revision(), body, key(), author))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> runbooks.updateDraft(draft.id(), edited.revision(), body, command, author))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test void adminRoleAndServiceScopeAreBothRequiredIncludingReplay() {
        var draft = create();
        var observer = new CurrentPrincipal("urn:test", "observer", Set.of(PlatformRole.OBSERVER), Set.of(SERVICE));
        var outsider = new CurrentPrincipal("urn:test", "writer", Set.of(PlatformRole.RUNBOOK_ADMIN), Set.of());
        assertThatThrownBy(() -> runbooks.review(draft.id(), 0, key(), observer)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> runbooks.get(draft.id(), outsider)).isInstanceOf(ApiProblemException.class);
        String command = key();
        runbooks.updateDraft(draft.id(), 0, new RunbookApplicationService.DraftContent(draft.definition(), "changed"), command, author);
        assertThatThrownBy(() -> runbooks.updateDraft(draft.id(), 0,
                new RunbookApplicationService.DraftContent(draft.definition(), "changed"), command, outsider))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test void concurrentVersionsHaveUniqueMonotonicNumbers() throws Exception {
        String runbookKey = "RB-" + key();
        var input = input(runbookKey);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> runbooks.createDraft(runbookKey, input, key(), author));
            var second = executor.submit(() -> runbooks.createDraft(runbookKey, input, key(), author));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS).versionNumber(), second.get(10, TimeUnit.SECONDS).versionNumber()))
                    .containsExactlyInAnyOrder(1, 2);
        }
    }

    @Test void diffIsVersionBoundAndReportsDefinitionAndMarkdown() {
        var first = create();
        var second = runbooks.createDraft(first.runbookKey(), input(first.runbookKey()), key(), author);
        second = runbooks.updateDraft(second.id(), second.revision(),
                new RunbookApplicationService.DraftContent(second.definition(), "Different description"), key(), author);
        var diff = runbooks.diff(first.id(), second.id(), author);
        assertThat(diff.definitionChanged()).isFalse();
        assertThat(diff.markdownChanged()).isTrue();
        var other = create();
        UUID secondId = second.id();
        assertThatThrownBy(() -> runbooks.diff(other.id(), secondId, author)).isInstanceOf(ApiProblemException.class);
    }

    @Test void concurrentPublishReplayProducesOneFrozenChunkSet() throws Exception {
        var draft = create();
        var reviewed = runbooks.review(draft.id(), draft.revision(), key(), reviewer);
        var providersReady = new CountDownLatch(2);
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            providersReady.countDown();
            assertThat(providersReady.await(10, TimeUnit.SECONDS)).isTrue();
            return new DeterministicEmbeddingGateway().embed(call.getArgument(0));
        });
        String command = key();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> runbooks.publish(draft.id(), reviewed.revision(), command, author));
            var second = executor.submit(() -> runbooks.publish(draft.id(), reviewed.revision(), command, author));
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(chunks(draft.id())).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from audit_record where resource_id=:id and action='runbook_published'")
                .param("id", draft.id().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test void selfDiffRemainsConsistentDuringConcurrentDraftEdits() throws Exception {
        var draft = create();
        var started = new CountDownLatch(1);
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                var current = draft;
                int edits = 0;
                while (!stopped.get() && edits < 300) {
                    current = runbooks.updateDraft(draft.id(), current.revision(),
                            new RunbookApplicationService.DraftContent(draft.definition(), "Revision " + ++edits), key(), author);
                    started.countDown();
                }
            });
            try {
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                for (int read = 0; read < 200; read++) {
                    var diff = runbooks.diff(draft.id(), draft.id(), author);
                    assertThat(diff.markdownChanged()).isFalse();
                    assertThat(diff.beforeMarkdown()).isEqualTo(diff.afterMarkdown());
                }
            } finally {
                stopped.set(true);
                writer.get(15, TimeUnit.SECONDS);
            }
        }
    }

    RunbookApplicationService.VersionView create() {
        String runbookKey = "RB-" + key();
        return runbooks.createDraft(runbookKey, input(runbookKey), key(), author);
    }
    RunbookApplicationService.VersionView publish(RunbookApplicationService.VersionView draft) {
        var reviewed = runbooks.review(draft.id(), draft.revision(), key(), reviewer);
        return runbooks.publish(draft.id(), reviewed.revision(), key(), author);
    }
    RunbookApplicationService.DraftInput input(String runbookKey) {
        var definition = json.readTree("""
                {"runbookKey":"placeholder","risk":"R1","adapterId":"demo-http",
                 "parameters":{"type":"object","properties":{"replicas":{"type":"integer","minimum":1,"maximum":1}},"required":["replicas"],"additionalProperties":false},
                 "steps":[{"stepId":"recover-one","operation":"recover_connection_pool"}],
                 "verification":{"probe":"demo_checkout_health","successThreshold":1.0,"attempts":6,"intervalSeconds":5},"rollback":null}
                """);
        ((tools.jackson.databind.node.ObjectNode) definition).put("runbookKey", runbookKey);
        return new RunbookApplicationService.DraftInput(SERVICE, "Pool recovery", "demo-sre", definition,
                "# Connection pool\n\nconnection pool timeout recovery for checkout");
    }
    long chunks(UUID id) { return jdbc.sql("select count(*) from knowledge_chunk where runbook_version_id=:id")
            .param("id", id).query(Long.class).single(); }
    static CurrentPrincipal principal(String subject) { return new CurrentPrincipal("urn:test", subject,
            Set.of(PlatformRole.RUNBOOK_ADMIN), Set.of(SERVICE)); }
    static String key() { return UUID.randomUUID().toString(); }
    static float[] invalidVector() { float[] value = new float[1536]; value[0] = Float.NaN; return value; }
}
