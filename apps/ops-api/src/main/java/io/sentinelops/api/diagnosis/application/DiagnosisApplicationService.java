package io.sentinelops.api.diagnosis.application;

import io.sentinelops.api.diagnosis.adapter.out.persistence.DiagnosisStore;
import io.sentinelops.api.diagnosis.adapter.out.persistence.DiagnosisCommandStore;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import io.sentinelops.api.diagnosis.application.model.ModelDiagnosisResult;
import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposal;
import io.sentinelops.api.diagnosis.domain.InvalidProposalException;
import io.sentinelops.api.incident.domain.IncidentCommand;
import io.sentinelops.api.incident.domain.IncidentStateMachine;
import io.sentinelops.api.incident.domain.IncidentStatus;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.AuthorizationService;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.knowledge.application.RunbookCatalog;
import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.shared.idempotency.IdempotencyService;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.shared.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Service
public class DiagnosisApplicationService {

    private final DiagnosisStore store;
    private final DiagnosisEngine engine;
    private final DiagnosisPolicy policy;
    private final ProposalHasher proposalHasher;
    private final RunbookCatalog runbooks;
    private final DiagnosisCommandStore commands;
    private final TransactionTemplate transactions;
    private final UuidV7Generator ids;
    private final TimeProvider time;
    private final ObjectMapper objectMapper;

    public DiagnosisApplicationService(
            DiagnosisStore store,
            DiagnosisEngine engine,
            DiagnosisPolicy policy,
            ProposalHasher proposalHasher,
            RunbookCatalog runbooks,
            DiagnosisCommandStore commands,
            PlatformTransactionManager transactionManager,
            UuidV7Generator ids,
            TimeProvider time,
            ObjectMapper objectMapper) {
        this.store = store;
        this.engine = engine;
        this.policy = policy;
        this.proposalHasher = proposalHasher;
        this.runbooks = runbooks;
        this.commands = commands;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.time = time;
        this.objectMapper = objectMapper;
    }

    public DiagnosisProposal diagnose(
            UUID incidentId,
            long expectedVersion,
            String idempotencyKey,
            CurrentPrincipal principal,
            UUID principalId) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("If-Match version must not be negative");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 200 characters");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Diagnosis requires a transaction-free caller");
        }
        var phase = transactions.execute(status -> claim(incidentId, expectedVersion, idempotencyKey, principal, principalId));
        IdempotencyService.Response response = phase.replay();
        if (response == null) {
            ModelDiagnosisResult draft = null;
            ApiProblemException failure = null;
            try { draft = engine.diagnoseWithMetadata(phase.context()); }
            catch (ApiProblemException known) { failure = known; }
            catch (RuntimeException unsafe) {
                failure = new ApiProblemException(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_FAILED", "Diagnosis provider failed.");
            }
            var result = draft;
            var problem = failure;
            response = transactions.execute(status -> finish(phase, result, problem, expectedVersion, principal));
        }

        if (response.status() != HttpStatus.CREATED.value()) {
            throw new ApiProblemException(
                    HttpStatus.valueOf(response.status()),
                    response.body().path("errorCode").asString("diagnosis_failed"),
                    response.body().path("detail").asString("Diagnosis validation failed."));
        }
        return objectMapper.readValue(
                objectMapper.writeValueAsString(response.body()), DiagnosisProposal.class);
    }

    private Phase claim(
            UUID incidentId,
            long expectedVersion,
            String key,
            CurrentPrincipal principal,
            UUID principalId) {
        var incident = store.lockIncident(incidentId).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, "incident_not_found", "The incident does not exist."));
        authorize(principal, incident.serviceId());
        var command = commands.claim(principal.principalKey(), key, requestHash(incidentId, expectedVersion));
        if (command.response() != null) return new Phase(command.id(), null, null, null, command.response());
        store.expireRuns(incidentId);
        var previous = store.findCommandRun(command.id());
        if (previous.isPresent()) {
            if (previous.get().status().equals("running")) throw new ApiProblemException(HttpStatus.CONFLICT,
                    "IDEMPOTENCY_IN_PROGRESS", "The diagnosis command is still in progress.");
            var response = failureResponse(HttpStatus.GATEWAY_TIMEOUT, previous.get().failureCode(), "The earlier diagnosis did not complete.");
            commands.complete(command.id(), response);
            return new Phase(command.id(), null, null, null, response);
        }
        if (incident.version() != expectedVersion) {
            throw new OptimisticLockingFailureException("Incident version does not match If-Match");
        }
        if (incident.status() != IncidentStatus.DETECTED
                && incident.status() != IncidentStatus.TRIAGING) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "incident_not_diagnosable",
                    "Only a detected or triaging incident can begin diagnosis.");
        }
        if (store.hasActiveRun(incidentId)) throw new ApiProblemException(HttpStatus.CONFLICT,
                "DIAGNOSIS_IN_PROGRESS", "An active diagnosis already exists for this incident.");

        var now = time.now();
        DiagnosisStore.IncidentSnapshot triagingIncident;
        long startSequence;
        if (incident.status() == IncidentStatus.DETECTED) {
            var triagingStatus = IncidentStateMachine.next(
                    incident.status(), IncidentCommand.START_TRIAGE);
            var triaging = store.transition(incident, triagingStatus, now);
            triagingIncident = triaging.incident();
            startSequence = triaging.sequence();
        } else {
            triagingIncident = incident;
            startSequence = store.allocateEvent(incidentId, now);
        }
        store.appendIncidentEvent(
                ids.generate(),
                incidentId,
                startSequence,
                "diagnosis_started",
                principal.subject(),
                objectMapper.writeValueAsString(objectMapper.createObjectNode()
                        .put("expectedIncidentVersion", expectedVersion)),
                now);

        var evidence = store.findEvidence(incidentId);
        UUID runId = ids.generate();
        var context = new DiagnosisContext(
                incidentId, triagingIncident.version(), incident.serviceId(), evidence,
                runId, now.minusSeconds(900), now, runbooks.publishedCorpusVersion(incident.serviceId()));
        UUID owner = ids.generate();
        store.insertRun(
                runId,
                incidentId,
                principalId,
                triagingIncident.version(),
                inputHash(context),
                now, command.id(), owner);
        evidence.forEach(snapshot -> store.linkRunEvidence(runId, snapshot.id()));
        return new Phase(command.id(), runId, owner, context, null);
    }

    private IdempotencyService.Response finish(Phase phase, ModelDiagnosisResult result,
            ApiProblemException providerFailure, long expectedVersion, CurrentPrincipal principal) {
        var incidentId = phase.context().incidentId();
        var runId = phase.runId();
        var triagingIncident = store.lockIncident(incidentId).orElseThrow();
        if (providerFailure instanceof io.sentinelops.api.diagnosis.application.model.ModelGatewayFailure modelFailure) {
            store.recordModelFailure(runId,phase.owner(),modelFailure);
        }
        IdempotencyService.Response response;
        if (!store.ownsLiveRun(runId, phase.owner())) {
            store.expireRuns(incidentId);
            response = failureResponse(HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT", "Diagnosis lease expired.");
        } else {
            try {
                authorize(principal, triagingIncident.serviceId());
                if (triagingIncident.version() != phase.context().incidentVersion()
                        || triagingIncident.status() != IncidentStatus.TRIAGING
                        || !triagingIncident.serviceId().equals(phase.context().serviceId())) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, "DIAGNOSIS_STALE", "Incident changed during diagnosis.");
                }
                if (!runbooks.publishedCorpusVersion(triagingIncident.serviceId()).equals(phase.context().runbookCorpusVersion())) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, "DIAGNOSIS_CORPUS_CHANGED", "Published Runbooks changed during diagnosis.");
                }
                if (providerFailure != null) throw providerFailure;
                if (result == null) throw new InvalidProposalException("MODEL_OUTPUT_INVALID", "Model returned no proposal.");
                // Only evidence actually linked to this run can be cited, including bounded tool captures.
                var context = new DiagnosisContext(incidentId, phase.context().incidentVersion(),
                        phase.context().serviceId(), store.findRunEvidence(runId), runId,
                        phase.context().evidenceFrom(), phase.context().evidenceTo(), phase.context().runbookCorpusVersion());
                store.recordModelMetadata(runId, result);
                response = persistProposal(result.proposal(), context, runId, phase.owner(), triagingIncident, expectedVersion, principal);
            } catch (ApiProblemException failure) {
                response = recordValidationFailure(incidentId, runId, principal.subject(), failure, time.now());
            }
        }
        return commands.complete(phase.commandId(), response);
    }

    private IdempotencyService.Response persistProposal(DiagnosisProposalDraft draft, DiagnosisContext context,
            UUID runId, UUID owner, DiagnosisStore.IncidentSnapshot triagingIncident, long expectedVersion, CurrentPrincipal principal) {
            var incidentId = context.incidentId();
            var runbook = draft.runbookVersionId() == null
                    ? null
                    : runbooks.lockVersion(draft.runbookVersionId()).orElse(null);
            var validated = policy.validate(draft, context, runbook);
            String proposalHash = proposalHasher.hash(validated);
            UUID proposalId = ids.generate();
            var createdAt = time.now();
            if (!store.succeedOwnedRun(runId, owner, createdAt)) {
                throw new ApiProblemException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT", "Diagnosis lease expired.");
            }
            store.insertProposal(
                    proposalId,
                    runId,
                    incidentId,
                    validated.runbookVersionId(),
                    validated.summary(),
                    objectMapper.writeValueAsString(validated),
                    proposalHash,
                    validated.riskLevel().databaseValue(),
                    createdAt);

            var citedEvidence = new LinkedHashSet<UUID>();
            validated.hypotheses()
                    .forEach(hypothesis -> citedEvidence.addAll(hypothesis.evidenceRefs()));
            citedEvidence.forEach(evidenceId -> store.linkEvidence(proposalId, evidenceId));

            var diagnosedStatus = IncidentStateMachine.next(
                    triagingIncident.status(), IncidentCommand.RECORD_DIAGNOSIS);
            var diagnosed = store.transition(triagingIncident, diagnosedStatus, createdAt);
            var eventPayload = objectMapper.createObjectNode()
                    .put("diagnosisRunId", runId.toString())
                    .put("proposalId", proposalId.toString())
                    .put("proposalHash", proposalHash);
            store.appendIncidentEvent(
                    ids.generate(),
                    incidentId,
                    diagnosed.sequence(),
                    "diagnosis_succeeded",
                    principal.subject(),
                    objectMapper.writeValueAsString(eventPayload),
                    createdAt);
            store.appendOutboxEvent(
                    ids.generate(),
                    incidentId,
                    "diagnosis.proposal-created",
                    objectMapper.writeValueAsString(eventPayload),
                    createdAt);

            var proposal = new DiagnosisProposal(
                    proposalId,
                    incidentId,
                    expectedVersion,
                    runId,
                    validated.summary(),
                    validated.hypotheses(),
                    validated.missingEvidence(),
                    validated.runbookVersionId(),
                    validated.parameters(),
                    validated.riskLevel(),
                    validated.expectedVerification(),
                    proposalHash,
                    createdAt);
            return new IdempotencyService.Response(
                    HttpStatus.CREATED.value(), objectMapper.valueToTree(proposal));
    }

    private void authorize(CurrentPrincipal principal, UUID serviceId) {
        AuthorizationService.require(principal, AuthorizationService.Action.DIAGNOSE, serviceId);
        if (!principal.hasAnyRole(
                        PlatformRole.ON_CALL_OPERATOR, PlatformRole.PLATFORM_ADMIN)
                || !principal.canAccess(serviceId)) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "access_denied",
                    "The principal cannot diagnose incidents for this service.");
        }
    }

    private IdempotencyService.Response recordValidationFailure(
            UUID incidentId,
            UUID runId,
            String principalKey,
            ApiProblemException failure,
            java.time.Instant failedAt) {
        store.completeRun(runId, "failed", failure.errorCode(), failedAt);
        long sequence = store.allocateEvent(incidentId, failedAt);
        var payload = objectMapper.createObjectNode()
                .put("diagnosisRunId", runId.toString())
                .put("failureCode", failure.errorCode());
        store.appendIncidentEvent(
                ids.generate(),
                incidentId,
                sequence,
                "diagnosis_validation_failed",
                principalKey,
                objectMapper.writeValueAsString(payload),
                failedAt);
        store.appendOutboxEvent(
                ids.generate(),
                incidentId,
                "diagnosis.validation-failed",
                objectMapper.writeValueAsString(payload),
                failedAt);
        var body = objectMapper.createObjectNode()
                .put("errorCode", failure.errorCode())
                .put("detail", failure.getMessage());
        return new IdempotencyService.Response(failure.status().value(), body);
    }

    private IdempotencyService.Response failureResponse(HttpStatus status, String code, String detail) {
        return new IdempotencyService.Response(status.value(), objectMapper.createObjectNode()
                .put("errorCode", code).put("detail", detail));
    }

    private record Phase(UUID commandId, UUID runId, UUID owner, DiagnosisContext context,
                         IdempotencyService.Response replay) {}

    private String requestHash(UUID incidentId, long expectedVersion) {
        return sha256(incidentId + ":" + expectedVersion);
    }

    private String inputHash(DiagnosisContext context) {
        var value = new StringBuilder()
                .append(context.incidentId())
                .append(':')
                .append(context.incidentVersion());
        context.evidence().forEach(evidence -> value.append(':')
                .append(evidence.id())
                .append(':')
                .append(evidence.contentHash()));
        return sha256(value.toString());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
