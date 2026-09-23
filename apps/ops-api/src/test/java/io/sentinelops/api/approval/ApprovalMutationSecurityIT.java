package io.sentinelops.api.approval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sentinelops.api.approval.adapter.in.web.ApprovalController;
import io.sentinelops.api.identity.application.AuthorizationService;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.shared.problem.ApiExceptionHandler;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalMutationSecurityIT {
    @Test
    void platformAdminWithoutApproverRoleCannotApprove() {
        var admin = new CurrentPrincipal("https://issuer.sentinelops.test", "admin",
                Set.of(PlatformRole.PLATFORM_ADMIN), Set.of());

        assertThatThrownBy(() -> AuthorizationService.require(
                admin, AuthorizationService.Action.APPROVE, UUID.randomUUID()))
                .isInstanceOf(ApiProblemException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
    }

    @Test
    void approverCannotAddExecutionParametersToDecisionBody() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ApprovalController(null, null))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == Jwt.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter,
                            ModelAndViewContainer modelAndViewContainer,
                            NativeWebRequest webRequest,
                            org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                        return Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .issuer("https://issuer.sentinelops.test")
                                .subject("approver")
                                .issuedAt(java.time.Instant.now())
                                .expiresAt(java.time.Instant.now().plusSeconds(300))
                                .build();
                    }
                }).build();

        mvc.perform(post("/api/v1/approval-requests/{id}/decisions", UUID.randomUUID())
                        .header("If-Match", "\"2\"")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"approve","comment":"ok",
                                 "proposalHash":"bound-hash","parameters":{"poolSize":999}}
                                """))
                .andExpect(status().isBadRequest());
    }
}
