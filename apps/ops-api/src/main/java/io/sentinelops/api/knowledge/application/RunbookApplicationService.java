package io.sentinelops.api.knowledge.application;

import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.AuthorizationService;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.identity.application.PrincipalLookup;
import io.sentinelops.api.knowledge.adapter.out.persistence.KnowledgeStore;
import io.sentinelops.api.knowledge.adapter.out.persistence.KnowledgeStore.Row;
import io.sentinelops.api.shared.idempotency.IdempotencyService;
import io.sentinelops.api.shared.audit.AuditCommand;
import io.sentinelops.api.shared.audit.AuditRecorder;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class RunbookApplicationService {
    private final KnowledgeStore store;
    private final RunbookDefinitionValidator validator;
    private final KnowledgeChunker chunker;
    private final ObjectProvider<EmbeddingGateway> providers;
    private final KnowledgeSearch search;
    private final PrincipalLookup principals;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final AuditRecorder audit;

    public RunbookApplicationService(KnowledgeStore store, RunbookDefinitionValidator validator,
            KnowledgeChunker chunker, ObjectProvider<EmbeddingGateway> providers, KnowledgeSearch search,
            PrincipalLookup principals, IdempotencyService idempotency, ObjectMapper json,
            AuditRecorder audit) {
        this.store = store; this.validator = validator; this.chunker = chunker; this.providers = providers;
        this.search = search; this.principals = principals; this.idempotency = idempotency; this.json = json;
        this.audit = audit;
    }

    public VersionView createDraft(String key, DraftInput input, String commandKey, CurrentPrincipal principal) {
        requireKey(key);
        Objects.requireNonNull(input, "input");
        authorize(principal, input.serviceId(), true);
        requireService(input.serviceId());
        validator.validate(key, input.definition());
        chunker.chunk(input.markdown());
        return command("create:" + key, commandKey, json.valueToTree(input), principal, () -> {
            var runbook = store.createAndLockRunbook(key, input.serviceId(), input.displayName(), input.ownerTeam());
            authorize(principal, runbook.serviceId(), true);
            if (!runbook.serviceId().equals(input.serviceId()) || !runbook.displayName().equals(input.displayName())
                    || !runbook.ownerTeam().equals(input.ownerTeam())) {
                throw conflict("runbook_identity_conflict", "The Runbook identity does not match the existing registration.");
            }
            var actor = principals.upsert(principal, principal.subject());
            var created = store.insertDraft(runbook.id(), actor, input.definition(), hash(input.definition()), input.markdown());
            audit(created, principal, "runbook_draft_created", null);
            return created;
        }, 201);
    }

    public VersionView updateDraft(UUID id, long revision, DraftContent input, String key, CurrentPrincipal principal) {
        var snapshot = authorized(id, principal);
        Objects.requireNonNull(input, "input");
        validator.validate(snapshot.runbookKey(), input.definition());
        chunker.chunk(input.markdown());
        return command("update:" + id, key, json.valueToTree(Map.of("revision", revision, "content", input)), principal, () -> {
            var current = lockDraft(id, revision, principal);
            var actor = principals.upsert(principal, principal.subject());
            var edited = store.updateDraft(id, actor, input.definition(), hash(input.definition()), input.markdown());
            audit(edited, principal, "runbook_draft_updated", current.checksum());
            return edited;
        }, 200);
    }

    public VersionView review(UUID id, long revision, String key, CurrentPrincipal principal) {
        authorized(id, principal);
        return command("review:" + id, key, json.valueToTree(Map.of("revision", revision)), principal, () -> {
            var current = lockDraft(id, revision, principal);
            validator.validate(current.runbookKey(), current.definition());
            var actor = principals.upsert(principal, principal.subject());
            if (actor.equals(current.authorId()) || actor.equals(current.lastEditorId())) {
                throw conflict("independent_review_required", "The author and editors cannot approve their own Runbook content.");
            }
            var reviewed = store.review(id, actor);
            audit(reviewed, principal, "runbook_reviewed", current.checksum());
            return reviewed;
        }, 200);
    }

    public VersionView publish(UUID id, long revision, String key, CurrentPrincipal principal) {
        requireOutsideTransaction();
        requireCommandKey(key);
        var snapshot = authorized(id, principal);
        Prepared prepared = null;
        if ("draft".equals(snapshot.lifecycle())) {
            requireRevision(snapshot, revision);
            requireReviewed(snapshot);
            validator.validate(snapshot.runbookKey(), snapshot.definition());
            var chunks = chunker.chunk(snapshot.markdown());
            var provider = provider();
            var embedded = embed(provider, chunks.stream().map(KnowledgeChunker.Chunk::content).toList());
            prepared = new Prepared(chunks, embedded.vectors(), embedded.model());
        }
        var ready = prepared;
        // Idempotency and the final state change share this short transaction. Provider calls never do.
        return command("publish:" + id, key, json.valueToTree(Map.of("revision", revision)), principal, () -> {
            var current = lockDraft(id, revision, principal);
            requireReviewed(current);
            if (ready == null || !current.checksum().equals(snapshot.checksum())
                    || !current.markdown().equals(snapshot.markdown()) || !current.definition().equals(snapshot.definition())
                    || !Objects.equals(current.reviewerId(), snapshot.reviewerId())) {
                throw new OptimisticLockingFailureException("Reviewed content changed during embedding");
            }
            store.insertChunks(current, ready.chunks(), ready.vectors(), ready.model());
            var published = store.publish(id);
            audit(published, principal, "runbook_published", current.checksum());
            return published;
        }, 200);
    }

    public VersionView get(UUID id, CurrentPrincipal principal) {
        var row = visible(id, principal);
        return view(row, principal, principals.findId(principal).orElse(null));
    }

    public List<RunbookSummary> list(String afterKey, int limit, CurrentPrincipal principal) {
        authorizeDirectory(principal);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("invalid Runbook page");
        String cursor = afterKey == null || afterKey.isEmpty() ? null : afterKey;
        if (cursor != null) requireKey(cursor);
        boolean platformAdmin = principal.hasAnyRole(PlatformRole.PLATFORM_ADMIN);
        boolean includeAdministrative = isRunbookAdministrator(principal);
        return store.runbookSummaries(cursor, limit, includeAdministrative, platformAdmin, principal.serviceIds()).stream()
                .filter(row -> principal.canAccess(row.serviceId()))
                .map(row -> new RunbookSummary(row.id(), row.runbookKey(), row.serviceId(), row.serviceKey(), row.displayName(), row.ownerTeam(),
                        row.latestVersionId(), row.latestVersionNumber(), row.lifecycle(), row.riskLevel().toUpperCase(java.util.Locale.ROOT)))
                .toList();
    }

    public List<VersionView> versions(String key, int afterVersion, int limit, CurrentPrincipal principal) {
        requireKey(key);
        if (afterVersion < 0 || limit < 1 || limit > 50) throw new IllegalArgumentException("invalid version page");
        var runbook = store.findRunbook(key).orElseThrow(RunbookApplicationService::notFound);
        boolean includeAdministrative = isRunbookAdministrator(principal);
        authorize(principal, runbook.serviceId(), false);
        UUID currentPrincipalId = principals.findId(principal).orElse(null);
        return store.versions(runbook.id(), afterVersion, limit, includeAdministrative).stream()
                .map(row -> view(row, principal, currentPrincipalId)).toList();
    }

    public VersionDiff diff(UUID from, UUID to, CurrentPrincipal principal) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        var pair = store.findPair(from, to);
        var before = pair.stream().filter(row -> row.id().equals(from)).findFirst().orElseThrow(RunbookApplicationService::notFound);
        var after = pair.stream().filter(row -> row.id().equals(to)).findFirst().orElseThrow(RunbookApplicationService::notFound);
        authorize(principal, before.serviceId(), true);
        authorize(principal, after.serviceId(), true);
        if (!before.runbookId().equals(after.runbookId())) {
            throw conflict("versions_must_share_runbook", "Compared versions must belong to the same Runbook.");
        }
        return new VersionDiff(from, to, !before.definition().equals(after.definition()), !before.markdown().equals(after.markdown()),
                before.definition(), after.definition(), before.markdown(), after.markdown());
    }

    public List<KnowledgeHit> search(UUID service, String query, int limit, CurrentPrincipal principal) {
        requireOutsideTransaction();
        authorize(principal, service, false);
        KnowledgeText.require(query, 1000);
        if (limit < 1 || limit > 10) {
            throw new IllegalArgumentException("invalid search budget");
        }
        requireService(service);
        var embedded = embed(provider(), List.of(query));
        return search.search(new KnowledgeQuery(service, query, embedded.vectors().getFirst(), embedded.model()), limit);
    }

    private VersionView command(String route, String key, JsonNode payload, CurrentPrincipal principal,
            Supplier<Row> action, int status) {
        requireCommandKey(key);
        var response = idempotency.execute(new IdempotencyService.Scope(principal.principalKey(), "runbook:" + route),
                key, hash(payload), () -> new IdempotencyService.Response(status,
                        json.valueToTree(view(action.get(), principal, principals.findId(principal).orElse(null)))));
        return json.treeToValue(response.body(), VersionView.class);
    }

    private Row authorized(UUID id, CurrentPrincipal principal) {
        Objects.requireNonNull(id, "id");
        var row = store.find(id).orElseThrow(RunbookApplicationService::notFound);
        authorize(principal, row.serviceId(), true);
        return row;
    }
    private Row visible(UUID id, CurrentPrincipal principal) {
        Objects.requireNonNull(id, "id");
        var row = store.find(id).orElseThrow(RunbookApplicationService::notFound);
        authorize(principal, row.serviceId(), false);
        if (!isRunbookAdministrator(principal) && !"published".equals(row.lifecycle())) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "access_denied", "The principal cannot view this Runbook version.");
        }
        return row;
    }
    private Row lockDraft(UUID id, long revision, CurrentPrincipal principal) {
        var row = store.lock(id).orElseThrow(RunbookApplicationService::notFound);
        authorize(principal, row.serviceId(), true);
        requireRevision(row, revision);
        if (!"draft".equals(row.lifecycle())) throw conflict("runbook_not_draft", "Only draft Runbook versions can change.");
        return row;
    }
    private static void requireRevision(Row row, long revision) {
        if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
        if (row.revision() != revision) throw new OptimisticLockingFailureException("Runbook revision does not match If-Match");
    }
    private static void requireReviewed(Row row) {
        if (row.reviewerId() == null || row.reviewedAt() == null || row.reviewerId().equals(row.authorId())
                || row.reviewerId().equals(row.lastEditorId())) {
            throw conflict("runbook_review_required", "Independent review is required before publication.");
        }
    }
    private static void authorize(CurrentPrincipal principal, UUID service, boolean admin) {
        Objects.requireNonNull(principal, "principal"); Objects.requireNonNull(service, "service");
        AuthorizationService.require(principal,
                admin ? AuthorizationService.Action.MANAGE_RUNBOOK : AuthorizationService.Action.VIEW_RUNBOOK,
                service);
        boolean role = admin ? principal.hasAnyRole(PlatformRole.RUNBOOK_ADMIN, PlatformRole.PLATFORM_ADMIN)
                : principal.hasAnyRole(PlatformRole.values());
        if (!role || !principal.canAccess(service)) throw new ApiProblemException(HttpStatus.FORBIDDEN, "access_denied", "The principal cannot access this Runbook service.");
    }
    private void audit(Row row, CurrentPrincipal principal, String action, String beforeChecksum) {
        audit.record(new AuditCommand(row.serviceId(), "user", principal.subject(), action,
                "runbook_version", row.id().toString(), "success", beforeChecksum,
                row.checksum(), null,
                Map.of("revision", row.revision(), "versionNumber", row.versionNumber())));
    }
    private static void authorizeDirectory(CurrentPrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        if (!principal.hasAnyRole(PlatformRole.OBSERVER, PlatformRole.ON_CALL_OPERATOR,
                PlatformRole.SRE_APPROVER, PlatformRole.RUNBOOK_ADMIN, PlatformRole.PLATFORM_ADMIN)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "access_denied", "The principal cannot access the Runbook catalog.");
        }
    }
    private static boolean isRunbookAdministrator(CurrentPrincipal principal) {
        return principal.hasAnyRole(PlatformRole.RUNBOOK_ADMIN, PlatformRole.PLATFORM_ADMIN);
    }
    private EmbeddingGateway provider() {
        var provider = providers.getIfAvailable();
        if (provider == null) throw unavailable();
        return provider;
    }
    private void requireService(UUID service) {
        if (!store.serviceExists(service)) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "service_not_found", "The requested service does not exist.");
        }
    }
    private Embedded embed(EmbeddingGateway provider, List<String> content) {
        try {
            String model = provider.modelId();
            KnowledgeQuery.validateModel(model);
            var result = new ArrayList<float[]>();
            for (int start = 0; start < content.size(); start += 100) {
                var batch = content.subList(start, Math.min(start + 100, content.size()));
                var response = provider.embed(List.copyOf(batch));
                if (response == null || response.size() != batch.size()) throw new IllegalArgumentException("embedding count mismatch");
                for (float[] vector : response) { KnowledgeQuery.validateEmbedding(vector); result.add(vector.clone()); }
            }
            if (!model.equals(provider.modelId())) throw new IllegalArgumentException("embedding model changed");
            return new Embedded(List.copyOf(result), model);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }
    private String hash(JsonNode value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(canonical(value)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var result = json.createObjectNode();
            value.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(item -> result.set(item.getKey(), canonical(item.getValue())));
            return result;
        }
        if (value.isArray()) { var result = json.createArrayNode(); value.forEach(item -> result.add(canonical(item))); return result; }
        return value;
    }
    private VersionView view(Row row, CurrentPrincipal principal, UUID currentPrincipalId) {
        boolean canReview = principal != null && isRunbookAdministrator(principal)
                && principal.canAccess(row.serviceId()) && "draft".equals(row.lifecycle())
                && (currentPrincipalId == null
                    || (!currentPrincipalId.equals(row.authorId()) && !currentPrincipalId.equals(row.lastEditorId())));
        return new VersionView(row.id(), row.runbookId(), row.runbookKey(), row.serviceId(), row.displayName(), row.ownerTeam(),
                row.versionNumber(), row.lifecycle(), row.revision(), row.definition(), row.markdown(), row.checksum(),
                row.authorId(), row.reviewerId(), row.publishedAt(), canReview);
    }
    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Embedding calls require no active transaction");
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException("invalid Runbook key");
    }
    private static void requireCommandKey(String key) {
        if (key == null || key.isBlank() || key.length() > 200) throw new IllegalArgumentException("invalid idempotency key");
    }
    private static ApiProblemException notFound() { return new ApiProblemException(HttpStatus.NOT_FOUND, "runbook_not_found", "The Runbook version does not exist."); }
    private static ApiProblemException conflict(String code, String detail) { return new ApiProblemException(HttpStatus.CONFLICT, code, detail); }
    private static ApiProblemException unavailable() { return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "embedding_unavailable", "The embedding provider is unavailable or returned invalid vectors."); }
    private static JsonNode definition(JsonNode value) {
        if (value == null || !value.isObject() || value.toString().length() > 32000) throw new IllegalArgumentException("invalid definition");
        return value.deepCopy();
    }
    private static void text(String value, int max) {
        KnowledgeText.require(value, max);
    }
    public record DraftInput(UUID serviceId, String displayName, String ownerTeam, JsonNode definition, String markdown) {
        public DraftInput { Objects.requireNonNull(serviceId, "serviceId"); text(displayName, 200); text(ownerTeam, 128);
            definition = RunbookApplicationService.definition(definition); text(markdown, 120000); }
        @Override public JsonNode definition() { return definition.deepCopy(); }
    }
    public record DraftContent(JsonNode definition, String markdown) {
        public DraftContent { definition = RunbookApplicationService.definition(definition); text(markdown, 120000); }
        @Override public JsonNode definition() { return definition.deepCopy(); }
    }
    public record VersionView(UUID id, UUID runbookId, String runbookKey, UUID serviceId, String displayName, String ownerTeam,
            int versionNumber, String lifecycle, long revision, JsonNode definition, String markdown, String definitionChecksum,
            UUID authorPrincipalId, UUID reviewerPrincipalId, Instant publishedAt, boolean canReview) {
        public VersionView { definition = definition.deepCopy(); }
        @Override public JsonNode definition() { return definition.deepCopy(); }
    }
    public record RunbookSummary(UUID id, String runbookKey, UUID serviceId, String serviceKey, String displayName, String ownerTeam,
            UUID latestVersionId, int latestVersionNumber, String lifecycle, String riskLevel) {}
    public record VersionDiff(UUID fromVersionId, UUID toVersionId, boolean definitionChanged, boolean markdownChanged,
            JsonNode beforeDefinition, JsonNode afterDefinition, String beforeMarkdown, String afterMarkdown) {
        public VersionDiff { beforeDefinition = beforeDefinition.deepCopy(); afterDefinition = afterDefinition.deepCopy(); }
        @Override public JsonNode beforeDefinition() { return beforeDefinition.deepCopy(); }
        @Override public JsonNode afterDefinition() { return afterDefinition.deepCopy(); }
    }
    private record Embedded(List<float[]> vectors, String model) {}
    private record Prepared(List<KnowledgeChunker.Chunk> chunks, List<float[]> vectors, String model) {}
}
