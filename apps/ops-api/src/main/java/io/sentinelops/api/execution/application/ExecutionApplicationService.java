package io.sentinelops.api.execution.application;

import io.sentinelops.api.execution.adapter.out.persistence.ExecutionStore;
import io.sentinelops.api.execution.adapter.out.persistence.ExecutionStore.CreationContext;
import io.sentinelops.api.execution.adapter.out.persistence.ExecutionStore.LeaseSnapshot;
import io.sentinelops.api.execution.adapter.out.persistence.OutboxStore;
import io.sentinelops.api.execution.adapter.out.persistence.VerificationStore;
import io.sentinelops.api.execution.application.ExecutionTicketVerifier.VerifiedTicket;
import io.sentinelops.api.execution.domain.Execution;
import io.sentinelops.api.execution.domain.ExecutionStatus;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.AuthorizationService;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.incident.domain.IncidentCommand;
import io.sentinelops.api.incident.domain.IncidentStateMachine;
import io.sentinelops.api.incident.domain.IncidentStatus;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.shared.audit.AuditCommand;
import io.sentinelops.api.shared.audit.AuditRecorder;
import io.sentinelops.api.shared.idempotency.IdempotencyService;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ExecutionApplicationService {

    private static final String CREATE_ROUTE = "POST:/api/v1/incidents/{incidentId}/executions";
    private static final String CLAIM_ROUTE = "POST:/internal/v1/executions/{id}:claim";
    private static final String HEARTBEAT_ROUTE = "POST:/internal/v1/executions/{id}:heartbeat";
    private static final String ATTEMPT_EVENT_ROUTE = "POST:/internal/v1/executions/{id}:attempt-events";
    private static final String COMPLETE_ROUTE = "POST:/internal/v1/executions/{id}:complete";
    private static final String FAIL_ROUTE = "POST:/internal/v1/executions/{id}:fail";

    private final ExecutionStore store;
    private final OutboxStore outbox;
    private final VerificationStore verificationStore;
    private final ExecutionTicketSigner ticketSigner;
    private final ExecutionTicketVerifier ticketVerifier;
    private final IdempotencyService idempotency;
    private final UuidV7Generator ids;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;
    private final String ticketIssuer;
    private final String ticketAudience;

    public ExecutionApplicationService(
            ExecutionStore store,
            OutboxStore outbox,
            VerificationStore verificationStore,
            ExecutionTicketSigner ticketSigner,
            ExecutionTicketVerifier ticketVerifier,
            IdempotencyService idempotency,
            UuidV7Generator ids,
            ObjectMapper objectMapper,
            AuditRecorder audit,
            @Value("${sentinelops.execution-ticket.issuer}") String ticketIssuer,
            @Value("${sentinelops.execution-ticket.audience}") String ticketAudience) {
        this.store = store;
        this.outbox = outbox;
        this.verificationStore = verificationStore;
        this.ticketSigner = ticketSigner;
        this.ticketVerifier = ticketVerifier;
        this.idempotency = idempotency;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.ticketIssuer = requireText(ticketIssuer, "ticketIssuer");
        this.ticketAudience = requireText(ticketAudience, "ticketAudience");
    }

    public Execution create(
            UUID incidentId,
            UUID proposalId,
            long expectedIncidentVersion,
            String idempotencyKey,
            CurrentPrincipal principal) {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(principal, "principal");
        if (expectedIncidentVersion < 0) {
            throw new IllegalArgumentException("If-Match version must not be negative");
        }
        var response = idempotency.execute(
                new IdempotencyService.Scope(principal.principalKey(), CREATE_ROUTE),
                idempotencyKey,
                sha256(incidentId + ":" + proposalId + ":" + expectedIncidentVersion),
                () -> createExecution(
                        incidentId, proposalId, expectedIncidentVersion, principal));
        if (response.status() != HttpStatus.CREATED.value()) {
            throw problem(
                    HttpStatus.valueOf(response.status()),
                    response.body().path("errorCode").asString("execution_creation_failed"),
                    response.body().path("detail").asString("Execution could not be created."));
        }
        return objectMapper.readValue(
                objectMapper.writeValueAsString(response.body()), Execution.class);
    }

    public ClaimView claim(UUID executionId, String executorId) {
        return signClaim(claimDraft(
                executionId, executorId, "direct:" + ids.generate()));
    }

    public ClaimView claim(
            UUID executionId,
            String executorId,
            String principalKey,
            String idempotencyKey) {
        var response = idempotency.executePostCommit(
                new IdempotencyService.Scope(principalKey, CLAIM_ROUTE),
                idempotencyKey,
                sha256(executionId + ":" + executorId),
                () -> claimDraft(executionId, executorId, idempotencyKey),
                () -> recoverClaimDraft(executionId, executorId, idempotencyKey),
                this::claimResponse);
        if (response.status() != HttpStatus.OK.value()) {
            throw problem(
                    HttpStatus.valueOf(response.status()),
                    response.body().path("errorCode").asString("execution_not_claimable"),
                    response.body().path("detail").asString("The execution cannot be claimed."));
        }
        return readResponse(response, ClaimView.class);
    }

    private ClaimDraft claimDraft(
            UUID executionId, String executorId, String claimAttemptKey) {
        Objects.requireNonNull(executionId, "executionId");
        executorId = requireText(executorId, "executorId");
        String ticketJti = ids.generate().toString();
        var outcome = store.claim(
                executionId,
                executorId,
                ticketJti,
                requireText(claimAttemptKey, "claimAttemptKey"),
                ids.generate());
        if (outcome.lease() != null) {
            return ClaimDraft.claimed(outcome.lease());
        }
        if (outcome.activeLease()) {
            return ClaimDraft.activeLeaseRejected();
        }
        if (outcome.unknownOutcome()) {
            return ClaimDraft.unknownOutcomeRejected();
        }
        return outcome.authorizationInvalidated()
                ? ClaimDraft.invalidatedAuthorization()
                : ClaimDraft.unavailable();
    }

    private ClaimDraft recoverClaimDraft(
            UUID executionId, String executorId, String claimAttemptKey) {
        return store.recoverClaim(
                        executionId,
                        requireText(executorId, "executorId"),
                        requireText(claimAttemptKey, "claimAttemptKey"))
                .map(ClaimDraft::claimed)
                .orElseGet(() -> claimDraft(executionId, executorId, claimAttemptKey));
    }

    private ClaimView signClaim(ClaimDraft draft) {
        ClaimProblem rejection = claimProblem(draft);
        if (rejection != null) {
            throw problem(rejection.status(), rejection.code(), rejection.detail());
        }
        return signLease(draft.lease());
    }

    private ClaimView signLease(ExecutionStore.ClaimLease lease) {
        Instant issuedAt = lease.ticketIssuedAt();
        var claims = new ExecutionTicketClaims(
                lease.ticketJti(),
                lease.executionId(),
                lease.incidentId(),
                lease.proposalId(),
                lease.runbookId(),
                lease.runbookVersionId(),
                lease.runbookChecksum(),
                lease.parameters(),
                lease.target(),
                lease.risk(),
                lease.adapterId(),
                lease.stepId(),
                lease.operation(),
                lease.fencingToken(),
                ticketIssuer,
                List.of(ticketAudience),
                issuedAt,
                issuedAt.minusSeconds(2),
                issuedAt.plusSeconds(60));
        return new ClaimView(
                lease.executionId(),
                lease.fencingToken(),
                lease.leaseUntil(),
                ticketSigner.sign(claims));
    }

    private IdempotencyService.Response claimResponse(ClaimDraft draft) {
        ClaimProblem rejection = claimProblem(draft);
        if (rejection != null) {
            var body = objectMapper.createObjectNode()
                    .put("errorCode", rejection.code())
                    .put("detail", rejection.detail());
            return new IdempotencyService.Response(rejection.status().value(), body);
        }
        return new IdempotencyService.Response(
                HttpStatus.OK.value(), objectMapper.valueToTree(signClaim(draft)));
    }

    private ClaimProblem claimProblem(ClaimDraft draft) {
        if (draft.lease() != null) {
            return null;
        }
        if (draft.activeLease()) {
            return new ClaimProblem(
                    HttpStatus.CONFLICT,
                    "execution_lease_active",
                    "The execution currently has an active lease.");
        }
        if (draft.authorizationInvalidated()) {
            return new ClaimProblem(
                    HttpStatus.CONFLICT,
                    "execution_authorization_invalidated",
                    "The execution authorization is no longer valid and was escalated.");
        }
        if (draft.unknownOutcome()) {
            return new ClaimProblem(
                    HttpStatus.CONFLICT,
                    "execution_outcome_unknown",
                    "The dispatched non-idempotent effect requires manual reconciliation.");
        }
        return new ClaimProblem(
                HttpStatus.CONFLICT,
                "execution_not_claimable",
                "The execution cannot be claimed from its current state.");
    }

    @Transactional
    public HeartbeatView heartbeat(
            UUID executionId,
            String executorId,
            long fencingToken,
            String executionTicket) {
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        var verifiedTicket = verifyTicket(executionTicket, executionId, fencingToken);
        var renewedLease = store.heartbeat(
                        executionId,
                        requireText(executorId, "executorId"),
                        fencingToken,
                        verifiedTicket.jti(),
                        ids.generate().toString(),
                        verifiedTicket.runbookChecksum())
                .orElseThrow(this::staleFencingToken);
        return heartbeatView(renewedLease);
    }

    public HeartbeatView heartbeat(
            UUID executionId,
            String executorId,
            long fencingToken,
            String executionTicket,
            String principalKey,
            String idempotencyKey) {
        var response = idempotency.execute(
                new IdempotencyService.Scope(principalKey, HEARTBEAT_ROUTE),
                idempotencyKey,
                sha256(executionId + ":" + executorId + ":" + fencingToken + ':'
                        + sha256(executionTicket)),
                () -> {
                    var verifiedTicket =
                            verifyTicket(executionTicket, executionId, fencingToken);
                    return new IdempotencyService.Response(
                            HttpStatus.OK.value(),
                            objectMapper.valueToTree(heartbeatVerified(
                                    executionId,
                                    executorId,
                                    fencingToken,
                                    verifiedTicket)));
                });
        return readResponse(response, HeartbeatView.class);
    }

    private HeartbeatView heartbeatVerified(
            UUID executionId,
            String executorId,
            long fencingToken,
            VerifiedTicket verifiedTicket) {
        var renewedLease = store.heartbeat(
                        executionId,
                        requireText(executorId, "executorId"),
                        fencingToken,
                        verifiedTicket.jti(),
                        ids.generate().toString(),
                        verifiedTicket.runbookChecksum())
                .orElseThrow(this::staleFencingToken);
        return heartbeatView(renewedLease);
    }

    private HeartbeatView heartbeatView(ExecutionStore.ClaimLease renewedLease) {
        var signed = signLease(renewedLease);
        return new HeartbeatView(
                signed.executionId(),
                signed.fencingToken(),
                signed.leaseUntil(),
                signed.ticket());
    }

    public AttemptPhaseView recordAttemptPhase(
            UUID executionId,
            String executorId,
            long fencingToken,
            String executionTicket,
            AttemptPhaseCommand command,
            String principalKey,
            String idempotencyKey) {
        Objects.requireNonNull(command, "command");
        var response = idempotency.execute(
                new IdempotencyService.Scope(principalKey, ATTEMPT_EVENT_ROUTE),
                idempotencyKey,
                sha256(executionId + ":" + executorId + ":" + fencingToken + ":"
                        + sha256(executionTicket) + ":"
                        + objectMapper.writeValueAsString(command)),
                () -> {
                    var ticket = verifyTicket(executionTicket, executionId, fencingToken);
                    if (!command.stepId().equals(ticket.stepId())) {
                        throw new InvalidExecutionTicket(
                                "Attempt phase step does not match the signed ticket");
                    }
                    UUID eventId = store.appendAttemptEvent(
                                    ids.generate(),
                                    executionId,
                                    requireText(executorId, "executorId"),
                                    ticket.jti(),
                                    ticket.runbookChecksum(),
                                    command.stepId(),
                                    command.attemptNo(),
                                    fencingToken,
                                    command.phase(),
                                    objectMapper.writeValueAsString(command.metadata()))
                            .orElseThrow(this::staleFencingToken);
                    if ("unknown_after_dispatch".equals(command.phase())) {
                        store.escalateCurrentUnknownOutcome(
                                executionId, command.stepId(), command.attemptNo(),
                                fencingToken, ids.generate());
                    }
                    return new IdempotencyService.Response(
                            HttpStatus.OK.value(),
                            objectMapper.valueToTree(new AttemptPhaseView(
                                    eventId, executionId, command.phase())));
                });
        return readResponse(response, AttemptPhaseView.class);
    }

    @Transactional
    public Execution complete(
            UUID executionId,
            String executorId,
            long fencingToken,
            String executionTicket,
            CompletionCommand command) {
        return finishVerified(
                executionId,
                executorId,
                fencingToken,
                verifyTicket(executionTicket, executionId, fencingToken),
                command,
                ExecutionStatus.VERIFYING,
                "succeeded",
                IncidentCommand.START_VERIFICATION,
                "execution_completed",
                false);
    }

    public Execution complete(
            UUID executionId,
            String executorId,
            long fencingToken,
            String executionTicket,
            CompletionCommand command,
            String principalKey,
            String idempotencyKey) {
        return finishIdempotently(
                executionId,
                executorId,
                fencingToken,
                executionTicket,
                command,
                principalKey,
                idempotencyKey,
                COMPLETE_ROUTE,
                ExecutionStatus.VERIFYING,
                "succeeded",
                IncidentCommand.START_VERIFICATION,
                "execution_completed");
    }

    @Transactional
    public Execution fail(
            UUID executionId,
            String executorId,
            long fencingToken,
            String executionTicket,
            CompletionCommand command) {
        return finishVerified(
                executionId,
                executorId,
                fencingToken,
                verifyTicket(executionTicket, executionId, fencingToken),
                command,
                ExecutionStatus.FAILED,
                "failed",
                IncidentCommand.ESCALATE,
                "execution_failed",
                false);
    }

    public Execution fail(
            UUID executionId,
            String executorId,
            long fencingToken,
            String executionTicket,
            CompletionCommand command,
            String principalKey,
            String idempotencyKey) {
        return finishIdempotently(
                executionId,
                executorId,
                fencingToken,
                executionTicket,
                command,
                principalKey,
                idempotencyKey,
                FAIL_ROUTE,
                ExecutionStatus.FAILED,
                "failed",
                IncidentCommand.ESCALATE,
                "execution_failed");
    }

    private Execution finishIdempotently(
            UUID executionId,
            String executorId,
            long fencingToken,
            String executionTicket,
            CompletionCommand command,
            String principalKey,
            String idempotencyKey,
            String route,
            ExecutionStatus executionTarget,
            String attemptOutcome,
            IncidentCommand incidentCommand,
            String eventType) {
        String requestBodyHash = sha256(executionId
                + ":"
                + executorId
                + ":"
                + fencingToken
                + ":"
                + sha256(executionTicket)
                + ":"
                + objectMapper.writeValueAsString(command));
        var response = idempotency.execute(
                new IdempotencyService.Scope(principalKey, route),
                idempotencyKey,
                requestBodyHash,
                () -> {
                    var verifiedTicket =
                            verifyTicket(executionTicket, executionId, fencingToken);
                    return new IdempotencyService.Response(
                            HttpStatus.OK.value(),
                            objectMapper.valueToTree(finishVerified(
                                    executionId,
                                    executorId,
                                    fencingToken,
                                    verifiedTicket,
                                    command,
                                    executionTarget,
                                    attemptOutcome,
                                    incidentCommand,
                                    eventType,
                                    true)));
                });
        return readResponse(response, Execution.class);
    }

    private IdempotencyService.Response createExecution(
            UUID incidentId,
            UUID proposalId,
            long expectedIncidentVersion,
            CurrentPrincipal principal) {
        var approval = store.lockApprovalForProposal(proposalId).orElseThrow(() -> problem(
                HttpStatus.CONFLICT,
                "approved_request_not_found",
                "The proposal does not have an active approval request."));
        if (!approval.incidentId().equals(incidentId)) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "proposal_incident_mismatch",
                    "The proposal does not belong to this incident.");
        }
        CreationContext context = store.lockCreationContext(incidentId, proposalId)
                .orElseThrow(() -> problem(
                        HttpStatus.CONFLICT,
                        "execution_context_missing",
                        "The proposal no longer has executable Runbook context."));
        authorizeOperator(principal, context.serviceId());
        if (!approval.proposalHash().equals(context.proposalHash())) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "APPROVAL_INVALIDATED",
                    "The approved proposal hash no longer matches the proposal.");
        }
        if (!approval.targetAlias().equals(context.target())) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "APPROVAL_INVALIDATED",
                    "The approved execution target no longer matches the service target.");
        }

        String businessKey = sha256(proposalId + ":" + context.proposalHash());
        var existing = store.findByBusinessKey(businessKey, context.incidentVersion());
        if (existing.isPresent()) {
            return response(HttpStatus.CREATED, existing.orElseThrow());
        }

        Instant databaseNow = store.databaseTime();
        if (!"approved".equals(approval.status())
                || !databaseNow.isBefore(approval.expiresAt())) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "approval_not_executable",
                    "The approval is not approved or has expired.");
        }
        if (!"published".equals(context.runbookLifecycle())
                || context.risk() == RiskLevel.R3) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "runbook_not_executable",
                    "The Runbook version is not an executable published version.");
        }
        if (context.target() == null || context.target().isBlank()) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "execution_target_missing",
                    "The incident service has no approved execution target alias.");
        }
        if (context.incidentVersion() != expectedIncidentVersion) {
            throw new OptimisticLockingFailureException(
                    "Incident version does not match If-Match");
        }
        if (context.incidentStatus() != IncidentStatus.AWAITING_APPROVAL) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "incident_not_executable",
                    "The incident is not awaiting an approved execution.");
        }

        UUID executionId = ids.generate();
        UUID eventId = ids.generate();
        store.insertExecution(executionId, approval, businessKey, databaseNow);
        var targetStatus = IncidentStateMachine.next(
                context.incidentStatus(), IncidentCommand.START_EXECUTION);
        var transition = store.transitionIncident(
                incidentId,
                context.incidentStatus(),
                targetStatus,
                context.incidentVersion(),
                databaseNow);
        var incidentPayload = objectMapper.createObjectNode()
                .put("executionId", executionId.toString())
                .put("proposalId", proposalId.toString())
                .put("approvalRequestId", approval.id().toString());
        store.appendIncidentEvent(
                ids.generate(),
                incidentId,
                transition.sequence(),
                "execution_requested",
                "user",
                principal.subject(),
                objectMapper.writeValueAsString(incidentPayload),
                databaseNow);
        var outboxPayload = objectMapper.createObjectNode()
                .put("eventId", eventId.toString())
                .put("eventType", "execution.requested.v1")
                .put("executionId", executionId.toString())
                .put("occurredAt", databaseNow.toString());
        outbox.insert(
                eventId,
                executionId,
                "execution.requested.v1",
                objectMapper.writeValueAsString(outboxPayload),
                databaseNow);
        audit.record(new AuditCommand(context.serviceId(), "user", principal.subject(),
                "execution_requested", "execution", executionId.toString(), "success",
                context.proposalHash(), context.runbookChecksum(), null,
                Map.of("status", "pending", "riskLevel", context.risk().name().toLowerCase(java.util.Locale.ROOT))));
        return response(
                HttpStatus.CREATED,
                new Execution(
                        executionId,
                        incidentId,
                        proposalId,
                        approval.id(),
                        ExecutionStatus.PENDING,
                        0,
                        transition.incidentVersion(),
                        databaseNow));
    }

    private Execution finishVerified(
            UUID executionId,
            String executorId,
            long fencingToken,
            VerifiedTicket verifiedTicket,
            CompletionCommand command,
            ExecutionStatus executionTarget,
            String attemptOutcome,
            IncidentCommand incidentCommand,
            String eventType,
            boolean requireJournal) {
        Objects.requireNonNull(command, "command");
        executorId = requireText(executorId, "executorId");
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        LeaseSnapshot lease = store.lockLease(executionId)
                .orElseThrow(() -> problem(
                        HttpStatus.NOT_FOUND,
                        "execution_not_found",
                        "The execution does not exist."));
        Instant databaseNow = store.databaseTime();
        validateLease(lease, executorId, fencingToken, verifiedTicket, databaseNow);
        if (!command.stepId().equals(verifiedTicket.stepId())
                || !command.adapterId().equals(verifiedTicket.adapterId())) {
            throw new InvalidExecutionTicket(
                    "Execution result step or adapter does not match the signed ticket");
        }
        var terminalPhase = store.appendResultPhase(
                ids.generate(), executionId, command.stepId(), command.attemptNo(),
                fencingToken, "succeeded".equals(attemptOutcome));
        if (requireJournal && terminalPhase.isEmpty()) {
            throw problem(HttpStatus.CONFLICT, "execution_attempt_phase_missing",
                    "The execution result needs a prepared and dispatched attempt record.");
        }
        store.insertAttempt(
                ids.generate(),
                executionId,
                command.stepId(),
                command.attemptNo(),
                fencingToken,
                command.adapterId(),
                command.adapterVersion(),
                command.requestHash(),
                attemptOutcome,
                objectMapper.writeValueAsString(command.sanitizedResult()),
                databaseNow);
        store.updateAfterAttempt(
                executionId, executionTarget, executorId, fencingToken, databaseNow);
        var incidentTarget = IncidentStateMachine.next(
                lease.incidentStatus(), incidentCommand);
        var transition = store.transitionIncident(
                lease.incidentId(),
                lease.incidentStatus(),
                incidentTarget,
                lease.incidentVersion(),
                databaseNow);
        if (executionTarget == ExecutionStatus.VERIFYING) {
            verificationStore.enqueueExecutionCycle(
                    ids.generate(), executionId, transition.incidentVersion(), databaseNow);
        }
        var payload = objectMapper.createObjectNode()
                .put("executionId", executionId.toString())
                .put("fencingToken", fencingToken)
                .put("outcome", attemptOutcome);
        store.appendIncidentEvent(
                ids.generate(),
                lease.incidentId(),
                transition.sequence(),
                eventType,
                "service",
                executorId,
                objectMapper.writeValueAsString(payload),
                databaseNow);
        return store.find(executionId).orElseThrow();
    }

    private void validateLease(
            LeaseSnapshot lease,
            String executorId,
            long fencingToken,
            VerifiedTicket verifiedTicket,
            Instant databaseNow) {
        if (lease.fencingToken() != fencingToken
                || !Objects.equals(lease.claimedBy(), executorId)
                || !Objects.equals(lease.ticketJti(), verifiedTicket.jti())
                || !Objects.equals(
                        lease.runbookChecksum(), verifiedTicket.runbookChecksum())
                || lease.leaseUntil() == null
                || !databaseNow.isBefore(lease.leaseUntil())) {
            throw staleFencingToken();
        }
        if (lease.status() != ExecutionStatus.RUNNING) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "execution_not_running",
                    "The execution is not running.");
        }
    }

    private VerifiedTicket verifyTicket(
            String token, UUID executionId, long fencingToken) {
        var verified = ticketVerifier.verify(
                token, ticketIssuer, ticketAudience, store.databaseTime());
        if (!verified.executionId().equals(executionId)
                || verified.fencingToken() != fencingToken) {
            throw new InvalidExecutionTicket(
                    "Execution ticket does not match the execution or fencing token");
        }
        return verified;
    }

    private void authorizeOperator(CurrentPrincipal principal, UUID serviceId) {
        AuthorizationService.require(principal, AuthorizationService.Action.EXECUTE, serviceId);
        if (!principal.hasAnyRole(
                        PlatformRole.ON_CALL_OPERATOR, PlatformRole.PLATFORM_ADMIN)
                || !principal.canAccess(serviceId)) {
            throw problem(
                    HttpStatus.FORBIDDEN,
                    "access_denied",
                    "The principal cannot execute changes for this service.");
        }
    }

    private ApiProblemException staleFencingToken() {
        return problem(
                HttpStatus.CONFLICT,
                "STALE_FENCING_TOKEN",
                "The execution lease or fencing token is stale.");
    }

    private IdempotencyService.Response response(HttpStatus status, Execution execution) {
        return new IdempotencyService.Response(
                status.value(), objectMapper.valueToTree(execution));
    }

    private <T> T readResponse(IdempotencyService.Response response, Class<T> type) {
        return objectMapper.readValue(
                objectMapper.writeValueAsString(response.body()), type);
    }

    private ApiProblemException problem(HttpStatus status, String code, String detail) {
        return new ApiProblemException(status, code, detail);
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record ClaimView(
            UUID executionId, long fencingToken, Instant leaseUntil, String ticket) {}

    public record HeartbeatView(
            UUID executionId, long fencingToken, Instant leaseUntil, String ticket) {}

    public record AttemptPhaseView(UUID eventId, UUID executionId, String phase) {}

    public record AttemptPhaseCommand(
            String stepId, int attemptNo, String phase, Map<String, Object> metadata) {
        public AttemptPhaseCommand {
            stepId = requireText(stepId, "stepId");
            if (attemptNo <= 0) {
                throw new IllegalArgumentException("attemptNo must be positive");
            }
            phase = requireText(phase, "phase");
            if (!List.of("prepared", "dispatched", "unknown_after_dispatch")
                    .contains(phase)) {
                throw new IllegalArgumentException("Unsupported attempt phase");
            }
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        }
    }

    private record ClaimDraft(
            ExecutionStore.ClaimLease lease,
            boolean authorizationInvalidated,
            boolean activeLease,
            boolean unknownOutcome) {

        private static ClaimDraft claimed(ExecutionStore.ClaimLease lease) {
            return new ClaimDraft(lease, false, false, false);
        }

        private static ClaimDraft invalidatedAuthorization() {
            return new ClaimDraft(null, true, false, false);
        }

        private static ClaimDraft activeLeaseRejected() {
            return new ClaimDraft(null, false, true, false);
        }

        private static ClaimDraft unknownOutcomeRejected() {
            return new ClaimDraft(null, false, false, true);
        }

        private static ClaimDraft unavailable() {
            return new ClaimDraft(null, false, false, false);
        }
    }

    private record ClaimProblem(HttpStatus status, String code, String detail) {}

    public record CompletionCommand(
            String stepId,
            int attemptNo,
            String adapterId,
            String adapterVersion,
            String requestHash,
            Map<String, Object> sanitizedResult) {

        public CompletionCommand {
            stepId = requireText(stepId, "stepId");
            if (attemptNo <= 0) {
                throw new IllegalArgumentException("attemptNo must be positive");
            }
            adapterId = requireText(adapterId, "adapterId");
            adapterVersion = requireText(adapterVersion, "adapterVersion");
            requestHash = requireText(requestHash, "requestHash");
            sanitizedResult = Map.copyOf(
                    Objects.requireNonNull(sanitizedResult, "sanitizedResult"));
        }
    }
}
