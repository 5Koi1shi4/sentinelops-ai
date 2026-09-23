package io.sentinelops.api.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.api.identity.application.AuthorizationService;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServiceScopeAuthorizationIT {
    private final UUID assigned = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void anOperatorCanReadAndExecuteOnlyInsideItsService() {
        var operator = principal(Set.of(PlatformRole.ON_CALL_OPERATOR), Set.of(assigned));
        assertThatCode(() -> AuthorizationService.require(
                operator, AuthorizationService.Action.EXECUTE, assigned)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AuthorizationService.require(
                operator, AuthorizationService.Action.EXECUTE, other))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> AuthorizationService.require(
                operator, AuthorizationService.Action.APPROVE, assigned))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void platformAdminIsGlobalButAuditorCanOnlyReadItsAssignedAudit() {
        var admin = principal(Set.of(PlatformRole.PLATFORM_ADMIN), Set.of());
        assertThatCode(() -> AuthorizationService.require(
                admin, AuthorizationService.Action.READ_EVIDENCE, other)).doesNotThrowAnyException();
        assertThatCode(() -> AuthorizationService.require(
                admin, AuthorizationService.Action.READ_AUDIT, null)).doesNotThrowAnyException();

        var auditor = principal(Set.of(PlatformRole.AUDITOR), Set.of(assigned));
        assertThatCode(() -> AuthorizationService.require(
                auditor, AuthorizationService.Action.READ_AUDIT, assigned)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AuthorizationService.require(
                auditor, AuthorizationService.Action.READ_AUDIT, other))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> AuthorizationService.require(
                auditor, AuthorizationService.Action.READ_AUDIT, null))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> AuthorizationService.require(
                auditor, AuthorizationService.Action.READ_EVIDENCE, assigned))
                .isInstanceOf(ApiProblemException.class);
    }

    private CurrentPrincipal principal(Set<PlatformRole> roles, Set<UUID> services) {
        return new CurrentPrincipal("https://issuer.sentinelops.test", "subject", roles, services);
    }
}
