package io.sentinelops.api.diagnosis.application;

import io.sentinelops.api.diagnosis.adapter.out.persistence.DiagnosisStore;
import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposal;
import io.sentinelops.api.diagnosis.domain.InvalidProposalException;
import io.sentinelops.api.incident.domain.IncidentCommand;
import io.sentinelops.api.incident.domain.IncidentStateMachine;
import io.sentinelops.api.incident.domain.IncidentStatus;
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
import tools.jackson.databind.ObjectMapper;

@Service
public class DiagnosisApplicationService {

    private static final String ROUTE_KEY = "POST:/api/v1/incidents/{id}/diagnosis-runs";

    private final DiagnosisStore store;
    private final DiagnosisEngine engine;
    private final DiagnosisPolicy policy;
    private final ProposalHasher proposalHasher;
    private final RunbookCatalog runbooks;
    private final IdempotencyService idempotency;
    private final UuidV7Generator ids;
    private final TimeProvider time;
    private final ObjectMapper objectMapper;

    public DiagnosisApplicationService(
            DiagnosisStore store,
            DiagnosisEngine engine,
            DiagnosisPolicy policy,
            ProposalHasher proposalHasher,
            RunbookCatalog runbooks,
            IdempotencyService idempotency,
            UuidV7Generator ids,
            TimeProvider time,
            ObjectMapper objectMapper) {
        this.store = store;
        this.engine = engine;
        this.policy = policy;
        this.proposalHasher = proposalHasher;
        this.runbooks = runbooks;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
        this.objectMapper = objectMapper;
    }

    public DiagnosisProposal diagnose(
            UUID incidentId,
            long expectedVersion,
            String idempotencyKey,
            String principalKey) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("If-Match version must not be negative");
        }
        var response = idempotency.execute(
                new IdempotencyService.Scope(principalKey, ROUTE_KEY),
                idempotencyKey,
                requestHash(incidentId, expectedVersion),
                () -> runDiagnosis(incidentId, expectedVersion, principalKey));

        if (response.status() != HttpStatus.CREATED.value()) {
            throw new ApiProblemException(
                    HttpStatus.valueOf(response.status()),
                    response.body().path("errorCode").asString("diagnosis_failed"),
                    response.body().path("detail").asString("Diagnosis validation failed."));
        }
        return objectMapper.readValue(
                objectMapper.writeValueAsString(response.body()), DiagnosisProposal.class);
    }

    private IdempotencyService.Response runDiagnosis(
            UUID incidentId, long expectedVersion, String principalKey) {
        var incident = store.lockIncident(incidentId).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, "incident_not_found", "The incident does not exist."));
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
                principalKey,
                objectMapper.writeValueAsString(objectMapper.createObjectNode()
                        .put("expectedIncidentVersion", expectedVersion)),
                now);

        var evidence = store.findEvidence(incidentId);
        var context = new DiagnosisContext(
                incidentId, expectedVersion, incident.serviceId(), evidence);
        UUID principalId = store.findPrincipalId(principalKey).orElseThrow(() -> new ApiProblemException(
                HttpStatus.UNAUTHORIZED,
                "principal_not_registered",
                "The requesting principal is not registered."));
        UUID runId = ids.generate();
        store.insertRun(
                runId,
                incidentId,
                principalId,
                expectedVersion,
                inputHash(context),
                now);
        evidence.forEach(snapshot -> store.linkRunEvidence(runId, snapshot.id()));

        try {
            var draft = engine.diagnose(context);
            var runbook = draft.runbookVersionId() == null
                    ? null
                    : runbooks.findVersion(draft.runbookVersionId()).orElse(null);
            var validated = policy.validate(draft, context, runbook);
            String proposalHash = proposalHasher.hash(validated);
            UUID proposalId = ids.generate();
            var createdAt = time.now();
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
            store.completeRun(runId, "succeeded", null, createdAt);

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
                    principalKey,
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
        } catch (InvalidProposalException failure) {
            return recordValidationFailure(
                    incidentId, runId, principalKey, failure, time.now());
        }
    }

    private IdempotencyService.Response recordValidationFailure(
            UUID incidentId,
            UUID runId,
            String principalKey,
            InvalidProposalException failure,
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
