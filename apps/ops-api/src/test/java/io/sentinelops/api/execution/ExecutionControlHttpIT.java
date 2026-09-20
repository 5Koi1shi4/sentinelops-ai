package io.sentinelops.api.execution;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.EXECUTOR_AUTHORITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sentinelops.api.execution.application.ExecutionApplicationService.ClaimView;
import io.sentinelops.api.execution.domain.Execution;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

class ExecutionControlHttpIT extends ExecutionFixtureSupport {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void operatorCreatesExecutionAndOnlyExecutorPrincipalCanClaimAndReadJwks()
            throws Exception {
        var fixture = approvedFixture();
        var creation = mockMvc.perform(post(
                                "/api/v1/incidents/{id}/executions",
                                fixture.incidentId())
                        .with(operator(fixture.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .header("Idempotency-Key", "http-create-" + fixture.proposalId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("proposalId", fixture.proposalId()))))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(creation.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"4\"");
        var execution = objectMapper.readValue(
                creation.getResponse().getContentAsString(), Execution.class);

        mockMvc.perform(post(
                                "/internal/v1/executions/{id}:claim",
                                execution.id())
                        .with(operator(fixture.serviceId()))
                        .header("Idempotency-Key", "wrong-audience-claim"))
                .andExpect(status().isForbidden());

        String claimKey = "http-claim-" + execution.id();
        var claimed = mockMvc.perform(post(
                                "/internal/v1/executions/{id}:claim",
                                execution.id())
                        .with(executor())
                        .header("Idempotency-Key", claimKey))
                .andExpect(status().isOk())
                .andReturn();
        var replayed = mockMvc.perform(post(
                                "/internal/v1/executions/{id}:claim",
                                execution.id())
                        .with(executor())
                        .header("Idempotency-Key", claimKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replayed.getResponse().getContentAsString())
                .isEqualTo(claimed.getResponse().getContentAsString());
        var claim = objectMapper.readValue(
                claimed.getResponse().getContentAsString(), ClaimView.class);
        assertThat(claim.ticket()).isNotBlank();

        var heartbeatBody = objectMapper.writeValueAsString(
                Map.of("fencingToken", claim.fencingToken()));
        mockMvc.perform(post(
                                "/internal/v1/executions/{id}:heartbeat",
                                execution.id())
                        .with(executor())
                        .header("Idempotency-Key", "heartbeat-missing-ticket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatBody))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(
                                "/internal/v1/executions/{id}:heartbeat",
                                execution.id())
                        .with(executor())
                        .header("Idempotency-Key", "heartbeat-tampered-ticket")
                        .header("X-SentinelOps-Execution-Ticket", claim.ticket() + "tampered")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatBody))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(
                                "/internal/v1/executions/{id}:heartbeat",
                                execution.id())
                        .with(executor())
                        .header("Idempotency-Key", "heartbeat-valid-ticket")
                        .header("X-SentinelOps-Execution-Ticket", claim.ticket())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatBody))
                .andExpect(status().isOk());

        var jwks = mockMvc.perform(get("/internal/v1/execution-keys/jwks.json")
                        .with(executor()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(jwks.getResponse().getContentAsString())
                        .path("keys")
                        .get(0)
                        .has("d"))
                .isFalse();
    }

    private RequestPostProcessor operator(UUID serviceId) {
        return jwt().authorities(
                        new SimpleGrantedAuthority(API_AUTHORITY),
                        new SimpleGrantedAuthority("ROLE_ON_CALL_OPERATOR"))
                .jwt(token -> token
                        .issuer("https://issuer.sentinelops.test")
                        .subject("http-operator")
                        .audience(List.of("sentinelops-api"))
                        .claim("realm_access", Map.of("roles", List.of("on_call_operator")))
                        .claim("service_ids", List.of(serviceId.toString())));
    }

    private RequestPostProcessor executor() {
        return jwt().authorities(new SimpleGrantedAuthority(EXECUTOR_AUTHORITY))
                .jwt(token -> token
                        .issuer("https://issuer.sentinelops.test")
                        .subject("http-executor")
                        .audience(List.of("sentinelops-executor"))
                        .claim("realm_access", Map.of("roles", List.of("sentinelops_executor")))
                        .claim("service_ids", List.of()));
    }
}
