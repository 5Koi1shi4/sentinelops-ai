package io.sentinelops.api.identity.application;

import io.sentinelops.api.shared.problem.ApiProblemException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** 在 HTTP 路由校验后再次执行应用层授权。 */
public final class AuthorizationService {
    private AuthorizationService() {}

    public enum Action {
        VIEW_INCIDENT, READ_EVIDENCE, DIAGNOSE, REQUEST_APPROVAL,
        APPROVE, EXECUTE, MANUAL_VERIFY, VIEW_RUNBOOK, MANAGE_RUNBOOK,
        RUN_EVAL, READ_AUDIT
    }

    public static void require(CurrentPrincipal principal, Action action, UUID serviceId) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(action, "action");
        if (action == Action.APPROVE && !principal.roles().contains(PlatformRole.SRE_APPROVER)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "access_denied",
                    "The principal cannot approve changes for the service.");
        }
        if (principal.roles().contains(PlatformRole.PLATFORM_ADMIN)) {
            return;
        }
        boolean roleAllowed = switch (action) {
            case VIEW_INCIDENT, READ_EVIDENCE, VIEW_RUNBOOK -> principal.hasAnyRole(
                    PlatformRole.OBSERVER, PlatformRole.ON_CALL_OPERATOR,
                    PlatformRole.SRE_APPROVER, PlatformRole.RUNBOOK_ADMIN);
            case DIAGNOSE, REQUEST_APPROVAL, EXECUTE, MANUAL_VERIFY ->
                    principal.hasAnyRole(PlatformRole.ON_CALL_OPERATOR);
            case APPROVE -> principal.hasAnyRole(PlatformRole.SRE_APPROVER);
            case MANAGE_RUNBOOK -> principal.hasAnyRole(PlatformRole.RUNBOOK_ADMIN);
            case RUN_EVAL -> false;
            case READ_AUDIT -> principal.hasAnyRole(PlatformRole.AUDITOR);
        };
        if (!roleAllowed || serviceId == null || !principal.serviceIds().contains(serviceId)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "access_denied",
                    "The principal cannot perform this action for the service.");
        }
    }
}
