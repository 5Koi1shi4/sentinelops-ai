package io.sentinelops.api.approval.application;

import io.sentinelops.api.approval.adapter.out.persistence.ApprovalStore;
import io.sentinelops.api.approval.adapter.out.persistence.ApprovalStore.DecisionCounts;
import io.sentinelops.api.approval.adapter.out.persistence.ApprovalStore.ProposalSnapshot;
import io.sentinelops.api.approval.adapter.out.persistence.ApprovalStore.RequestSnapshot;
import io.sentinelops.api.approval.domain.ApprovalDecision;
import io.sentinelops.api.approval.domain.ApprovalPolicy;
import io.sentinelops.api.approval.domain.ApprovalStatus;
import io.sentinelops.api.approval.domain.SeparationOfDutiesException;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.AuthorizationService;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.incident.domain.IncidentCommand;
import io.sentinelops.api.incident.domain.IncidentStateMachine;
import io.sentinelops.api.incident.domain.IncidentStatus;
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
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ApprovalApplicationService {

    private static final String REQUEST_ROUTE =
            "POST:/api/v1/incidents/{incidentId}/approval-requests";
    private static final String DECISION_ROUTE =
            "POST:/api/v1/approval-requests/{requestId}/decisions";

    private final ApprovalStore store;
    private final ApprovalPolicy policy;
    private final IdempotencyService idempotency;
    private final UuidV7Generator ids;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;

    public ApprovalApplicationService(
            ApprovalStore store,
            ApprovalPolicy policy,
            IdempotencyService idempotency,
            UuidV7Generator ids,
            ObjectMapper objectMapper,
            AuditRecorder audit) {
        this.store = store;
        this.policy = policy;
        this.idempotency = idempotency;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    public ApprovalView request(
            UUID incidentId,
            UUID proposalId,
            long expectedIncidentVersion,
            String idempotencyKey,
            RequestContext context) {
        requireVersion(expectedIncidentVersion);
        var response = idempotency.execute(
                new IdempotencyService.Scope(context.principal().principalKey(), REQUEST_ROUTE),
                idempotencyKey,
                hash("request:" + incidentId + ':' + proposalId + ':' + expectedIncidentVersion),
                () -> createRequest(
                        incidentId, proposalId, expectedIncidentVersion, context));
        return successOrThrow(response, HttpStatus.CREATED);
    }

    public ApprovalView decide(
            UUID requestId,
            long expectedIncidentVersion,
            String idempotencyKey,
            DecisionCommand command,
            RequestContext context) {
        requireVersion(expectedIncidentVersion);
        Objects.requireNonNull(command, "command");
        var response = idempotency.execute(
                new IdempotencyService.Scope(context.principal().principalKey(), DECISION_ROUTE),
                idempotencyKey,
                hash("decision:"
                        + requestId
                        + ':'
                        + expectedIncidentVersion
                        + ':'
                        + command.decision()
                        + ':'
                        + command.comment()
                        + ':'
                        + command.proposalHash()),
                () -> recordDecision(
                        requestId, expectedIncidentVersion, command, context));
        return successOrThrow(response, HttpStatus.OK);
    }

    private IdempotencyService.Response createRequest(
            UUID incidentId,
            UUID proposalId,
            long expectedIncidentVersion,
            RequestContext context) {
        ProposalSnapshot proposal = store.lockProposalIncident(proposalId)
                .orElseThrow(() -> problem(
                        HttpStatus.NOT_FOUND,
                        "proposal_not_found",
                        "The diagnosis proposal does not exist."));
        if (!proposal.incidentId().equals(incidentId)) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "proposal_incident_mismatch",
                    "The proposal does not belong to this incident.");
        }
        authorizeRequester(context.principal(), proposal.serviceId());
        if (proposal.incidentVersion() != expectedIncidentVersion) {
            throw new OptimisticLockingFailureException(
                    "Incident version does not match If-Match");
        }
        if (proposal.incidentStatus() != IncidentStatus.DIAGNOSED) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "incident_not_awaiting_approval",
                    "Only a diagnosed incident can request approval.");
        }
        if (proposal.targetAlias() == null || proposal.targetAlias().isBlank()) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "execution_target_missing",
                    "The incident service has no execution target to bind to the approval.");
        }

        var requirements = policy.requirements(proposal.riskLevel());
        Instant createdAt = store.databaseTime();
        UUID requestId = ids.generate();
        store.insertRequest(
                requestId,
                proposal,
                context.principalId(),
                requirements.requiredApprovals(),
                requirements.independentApproverRequired(),
                createdAt,
                createdAt.plus(requirements.validity()));
        IncidentStateMachine.next(
                proposal.incidentStatus(), IncidentCommand.REQUEST_APPROVAL);
        var transition = store.transitionToAwaitingApproval(proposal, createdAt);
        var payload = objectMapper.createObjectNode()
                .put("approvalRequestId", requestId.toString())
                .put("proposalId", proposal.proposalId().toString())
                .put("proposalHash", proposal.proposalHash())
                .put("targetAlias", proposal.targetAlias())
                .put("requiredApprovals", requirements.requiredApprovals());
        store.appendIncidentEvent(
                ids.generate(),
                incidentId,
                transition.sequence(),
                "approval_requested",
                context.principal().subject(),
                objectMapper.writeValueAsString(payload),
                createdAt);
        audit.record(new AuditCommand(proposal.serviceId(), "user", context.principal().subject(),
                "approval_requested", "approval_request", requestId.toString(), "success",
                null, proposal.proposalHash(), null,
                java.util.Map.of("status", "pending")));

        return response(
                HttpStatus.CREATED,
                new ApprovalView(
                        requestId,
                        incidentId,
                        proposalId,
                        proposal.proposalHash(),
                        proposal.targetAlias(),
                        ApprovalStatus.PENDING,
                        0,
                        requirements.requiredApprovals(),
                        0,
                        0,
                        transition.incidentVersion(),
                        createdAt.plus(requirements.validity())));
    }

    private IdempotencyService.Response recordDecision(
            UUID requestId,
            long expectedIncidentVersion,
            DecisionCommand command,
            RequestContext context) {
        RequestSnapshot request = store.lockRequest(requestId)
                .orElseThrow(() -> problem(
                        HttpStatus.NOT_FOUND,
                        "approval_request_not_found",
                        "The approval request does not exist."));
        ProposalSnapshot proposal = store.lockProposalIncidentForDecision(request.proposalId())
                .orElseThrow(() -> problem(
                        HttpStatus.CONFLICT,
                        "APPROVAL_INVALIDATED",
                        "The bound proposal no longer exists."));
        if (!request.incidentId().equals(proposal.incidentId())) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "approval_binding_invalid",
                    "The approval request no longer matches its incident.");
        }
        authorizeApprover(context.principal(), proposal.serviceId());
        if (proposal.incidentVersion() != expectedIncidentVersion) {
            throw new OptimisticLockingFailureException(
                    "Incident version does not match If-Match");
        }
        if (request.status() != ApprovalStatus.PENDING) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "approval_not_pending",
                    "The approval request is no longer pending.");
        }
        if (request.independentApproverRequired()
                && request.requesterId().equals(context.principalId())) {
            throw new SeparationOfDutiesException();
        }

        Instant databaseNow = store.databaseTime();
        if (!databaseNow.isBefore(request.expiresAt())) {
            return invalidate(
                    request,
                    proposal,
                    context,
                    ApprovalStatus.EXPIRED,
                    "expired",
                    databaseNow);
        }
        if (proposal.incidentStatus() != IncidentStatus.AWAITING_APPROVAL) {
            return invalidate(
                    request,
                    proposal,
                    context,
                    ApprovalStatus.INVALIDATED,
                    "incident_state_changed",
                    databaseNow);
        }
        if (!request.proposalHash().equals(proposal.proposalHash())) {
            return invalidate(
                    request,
                    proposal,
                    context,
                    ApprovalStatus.INVALIDATED,
                    "proposal_hash_changed",
                    databaseNow);
        }
        if (!request.targetAlias().equals(proposal.targetAlias())) {
            return invalidate(
                    request,
                    proposal,
                    context,
                    ApprovalStatus.INVALIDATED,
                    "execution_target_changed",
                    databaseNow);
        }
        if (!command.proposalHash().equals(proposal.proposalHash())) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "proposal_hash_mismatch",
                    "The supplied proposal hash does not match the approval request.");
        }
        if (store.hasDecision(requestId, context.principalId())) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "approval_already_decided",
                    "This reviewer already recorded a decision.");
        }

        store.insertDecision(
                ids.generate(),
                requestId,
                context.principalId(),
                command.decision(),
                command.comment(),
                command.proposalHash(),
                databaseNow);
        DecisionCounts counts = store.countDecisions(requestId);
        ApprovalStatus nextStatus = nextStatus(request, counts);
        long requestVersion = store.updateAfterDecision(requestId, nextStatus, databaseNow);
        long incidentVersion = proposal.incidentVersion();
        if (nextStatus != ApprovalStatus.PENDING) {
            incidentVersion = appendTerminalEvent(
                    request,
                    proposal,
                    counts,
                    nextStatus,
                    context.principal().subject(),
                    databaseNow);
        }
        audit.record(new AuditCommand(proposal.serviceId(), "user", context.principal().subject(),
                "approval_decided", "approval_request", requestId.toString(), "success",
                request.proposalHash(), hash(command.decision() + ":" + requestVersion), null,
                java.util.Map.of("decision", command.decision().name().toLowerCase(java.util.Locale.ROOT),
                        "status", nextStatus.databaseValue())));
        return response(
                HttpStatus.OK,
                view(
                        request,
                        proposal.proposalHash(),
                        nextStatus,
                        requestVersion,
                        counts,
                        incidentVersion));
    }

    private IdempotencyService.Response invalidate(
            RequestSnapshot request,
            ProposalSnapshot proposal,
            RequestContext context,
            ApprovalStatus status,
        String reason,
        Instant invalidatedAt) {
        store.invalidate(request.id(), status, invalidatedAt);
        var eventAllocation = proposal.incidentStatus() == IncidentStatus.AWAITING_APPROVAL
                ? escalateIncident(proposal, invalidatedAt)
                : new ApprovalStore.IncidentEventAllocation(
                        proposal.incidentVersion(),
                        store.allocateIncidentEvent(request.incidentId(), invalidatedAt));
        var payload = objectMapper.createObjectNode()
                .put("approvalRequestId", request.id().toString())
                .put("status", status.databaseValue())
                .put("reason", reason);
        store.appendIncidentEvent(
                ids.generate(),
                request.incidentId(),
                eventAllocation.sequence(),
                "approval_invalidated",
                context.principal().subject(),
                objectMapper.writeValueAsString(payload),
                invalidatedAt);
        audit.record(new AuditCommand(proposal.serviceId(), "user", context.principal().subject(),
                "approval_invalidated", "approval_request", request.id().toString(), "failure",
                request.proposalHash(), null, null,
                java.util.Map.of("status", status.databaseValue(), "reasonCode", reason)));
        var body = objectMapper.createObjectNode()
                .put("errorCode", "APPROVAL_INVALIDATED")
                .put("detail", "The approval request is expired or no longer matches its proposal.");
        return new IdempotencyService.Response(HttpStatus.CONFLICT.value(), body);
    }

    private long appendTerminalEvent(
            RequestSnapshot request,
            ProposalSnapshot proposal,
            DecisionCounts counts,
            ApprovalStatus status,
            String actorId,
            Instant occurredAt) {
        var eventAllocation = status == ApprovalStatus.REJECTED
                ? escalateIncident(proposal, occurredAt)
                : new ApprovalStore.IncidentEventAllocation(
                        proposal.incidentVersion(),
                        store.allocateIncidentEvent(request.incidentId(), occurredAt));
        var payload = objectMapper.createObjectNode()
                .put("approvalRequestId", request.id().toString())
                .put("approvals", counts.approvals())
                .put("rejections", counts.rejections());
        store.appendIncidentEvent(
                ids.generate(),
                request.incidentId(),
                eventAllocation.sequence(),
                status == ApprovalStatus.APPROVED
                        ? "approval_granted"
                        : "approval_rejected",
                actorId,
                objectMapper.writeValueAsString(payload),
                occurredAt);
        return eventAllocation.incidentVersion();
    }

    private ApprovalStore.IncidentEventAllocation escalateIncident(
            ProposalSnapshot proposal, Instant occurredAt) {
        IncidentStateMachine.next(proposal.incidentStatus(), IncidentCommand.ESCALATE);
        return store.transitionToEscalated(
                proposal.incidentId(), proposal.incidentVersion(), occurredAt);
    }

    private ApprovalStatus nextStatus(RequestSnapshot request, DecisionCounts counts) {
        if (counts.rejections() > 0) {
            return ApprovalStatus.REJECTED;
        }
        if (counts.approvals() >= request.requiredApprovals()) {
            return ApprovalStatus.APPROVED;
        }
        return ApprovalStatus.PENDING;
    }

    private ApprovalView view(
            RequestSnapshot request,
            String proposalHash,
            ApprovalStatus status,
            long requestVersion,
            DecisionCounts counts,
            long incidentVersion) {
        return new ApprovalView(
                request.id(),
                request.incidentId(),
                request.proposalId(),
                proposalHash,
                request.targetAlias(),
                status,
                requestVersion,
                request.requiredApprovals(),
                counts.approvals(),
                counts.rejections(),
                incidentVersion,
                request.expiresAt());
    }

    private IdempotencyService.Response response(HttpStatus status, ApprovalView view) {
        return new IdempotencyService.Response(status.value(), objectMapper.valueToTree(view));
    }

    private ApprovalView successOrThrow(
            IdempotencyService.Response response, HttpStatus expectedStatus) {
        if (response.status() != expectedStatus.value()) {
            throw problem(
                    HttpStatus.valueOf(response.status()),
                    response.body().path("errorCode").asString("approval_failed"),
                    response.body().path("detail").asString("The approval command failed."));
        }
        return objectMapper.readValue(
                objectMapper.writeValueAsString(response.body()), ApprovalView.class);
    }

    private void authorizeRequester(CurrentPrincipal principal, UUID serviceId) {
        AuthorizationService.require(principal, AuthorizationService.Action.REQUEST_APPROVAL, serviceId);
        if (!principal.hasAnyRole(
                        PlatformRole.ON_CALL_OPERATOR, PlatformRole.PLATFORM_ADMIN)
                || !principal.canAccess(serviceId)) {
            throw problem(
                    HttpStatus.FORBIDDEN,
                    "access_denied",
                    "The principal cannot request approval for this service.");
        }
    }

    private void authorizeApprover(CurrentPrincipal principal, UUID serviceId) {
        AuthorizationService.require(principal, AuthorizationService.Action.APPROVE, serviceId);
        if (!principal.hasAnyRole(PlatformRole.SRE_APPROVER, PlatformRole.PLATFORM_ADMIN)
                || !principal.canAccess(serviceId)) {
            throw problem(
                    HttpStatus.FORBIDDEN,
                    "access_denied",
                    "The principal cannot approve changes for this service.");
        }
    }

    private ApiProblemException problem(HttpStatus status, String code, String detail) {
        return new ApiProblemException(status, code, detail);
    }

    private void requireVersion(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("If-Match version must not be negative");
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    public record RequestContext(CurrentPrincipal principal, UUID principalId) {
        public RequestContext {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(principalId, "principalId");
        }
    }

    public record DecisionCommand(
            ApprovalDecision decision, String comment, String proposalHash) {
        public DecisionCommand {
            Objects.requireNonNull(decision, "decision");
            comment = comment == null ? "" : comment.trim();
            if (comment.length() > 2000) {
                throw new IllegalArgumentException("comment must not exceed 2000 characters");
            }
            if (proposalHash == null || proposalHash.isBlank()) {
                throw new IllegalArgumentException("proposalHash must not be blank");
            }
            proposalHash = proposalHash.trim();
        }
    }

    public record ApprovalView(
            UUID id,
            UUID incidentId,
            UUID proposalId,
            String proposalHash,
            String targetAlias,
            ApprovalStatus status,
            long resourceVersion,
            int requiredApprovals,
            int approvals,
            int rejections,
            long incidentVersion,
            Instant expiresAt) {}
}
