package io.sentinelops.api.approval.adapter.in.web;

import io.sentinelops.api.approval.application.ApprovalApplicationService;
import io.sentinelops.api.approval.application.ApprovalApplicationService.ApprovalView;
import io.sentinelops.api.approval.application.ApprovalApplicationService.DecisionCommand;
import io.sentinelops.api.approval.application.ApprovalApplicationService.RequestContext;
import io.sentinelops.api.approval.domain.ApprovalDecision;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PrincipalLookup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ApprovalController {

    private final ApprovalApplicationService approvals;
    private final PrincipalLookup principals;

    public ApprovalController(
            ApprovalApplicationService approvals, PrincipalLookup principals) {
        this.approvals = approvals;
        this.principals = principals;
    }

    @PostMapping("/incidents/{incidentId}/approval-requests")
    ResponseEntity<ApprovalView> request(
            @PathVariable UUID incidentId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RequestApprovalBody body,
            @AuthenticationPrincipal Jwt jwt) {
        var context = context(jwt);
        var created = approvals.request(
                incidentId,
                body.proposalId(),
                parseVersion(ifMatch),
                idempotencyKey,
                context);
        return ResponseEntity.created(URI.create("/api/v1/approval-requests/" + created.id()))
                .eTag(Long.toString(created.incidentVersion()))
                .body(created);
    }

    @PostMapping("/approval-requests/{requestId}/decisions")
    ResponseEntity<ApprovalView> decide(
            @PathVariable UUID requestId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DecisionBody body,
            @AuthenticationPrincipal Jwt jwt) {
        var decided = approvals.decide(
                requestId,
                parseVersion(ifMatch),
                idempotencyKey,
                new DecisionCommand(
                        ApprovalDecision.fromValue(body.decision()),
                        body.comment(),
                        body.proposalHash()),
                context(jwt));
        return ResponseEntity.status(HttpStatus.OK)
                .eTag(Long.toString(decided.incidentVersion()))
                .body(decided);
    }

    private RequestContext context(Jwt jwt) {
        var principal = CurrentPrincipal.from(jwt);
        String displayName = jwt.getClaimAsString("name");
        UUID principalId = principals.upsert(
                principal,
                displayName == null || displayName.isBlank()
                        ? principal.subject()
                        : displayName);
        return new RequestContext(principal, principalId);
    }

    private long parseVersion(String ifMatch) {
        String value = ifMatch == null ? "" : ifMatch.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("If-Match must contain one incident version");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "If-Match version is outside the supported range", failure);
        }
    }

    public record RequestApprovalBody(@NotNull UUID proposalId) {}

    public record DecisionBody(
            @NotBlank String decision,
            String comment,
            @NotBlank String proposalHash) {}
}
