package io.sentinelops.demo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
    "sentinelops.demo-mode=true",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost.invalid/demo-jwks",
    "sentinelops.security.issuer=https://issuer.sentinelops.test",
    "sentinelops.security.audience=demo-service"
})
class DemoFaultFlowIT {

    @Autowired private WebApplicationContext context;
    @Autowired private Environment environment;

    private MockMvc mockMvc;

    @BeforeEach
    void clearFault() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        mockMvc.perform(post("/internal/runbooks/recover-connection-pool")
                        .with(runbookExecutor())
                        .header("Idempotency-Key", "test-reset-" + UUID.randomUUID())
                        .header("X-SentinelOps-Fencing-Token", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void usesTheDedicatedDemoPortByDefault() {
        assertThat(environment.getProperty("server.port")).isEqualTo("8082");
    }

    @Test
    void exposesPrometheusMetricsToTheInternalScraper() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "demo_fault_active")));
    }

    @Test
    void checkoutFailsDuringFaultAndRecoversThroughRunbookEndpoint() throws Exception {
        mockMvc.perform(post("/api/checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .with(faultController()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.changed").value(true));

        mockMvc.perform(post("/api/checkout")
                        .header("X-Request-ID", "checkout-during-fault"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("demo_connection_pool_exhausted"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(post("/internal/runbooks/recover-connection-pool")
                        .with(runbookExecutor())
                        .header("Idempotency-Key", "execution:recover-one")
                        .header("X-SentinelOps-Fencing-Token", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
        mockMvc.perform(post("/internal/runbooks/recover-connection-pool")
                        .with(runbookExecutor())
                        .header("Idempotency-Key", "execution:recover-one-retry")
                        .header("X-SentinelOps-Fencing-Token", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));

        mockMvc.perform(post("/api/checkout"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }

    @Test
    void wrongCredentialsAndScopesCannotChangeFaultState() throws Exception {
        mockMvc.perform(post("/internal/demo/faults/connection-pool"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .with(runbookExecutor()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/runbooks/recover-connection-pool")
                        .with(faultController())
                        .header("Idempotency-Key", "wrong-scope")
                        .header("X-SentinelOps-Fencing-Token", "1"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/checkout"))
                .andExpect(status().isOk());
    }

    @Test
    void faultCanBeClearedByTheSeparateDemoControllerCredential() throws Exception {
        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .with(faultController()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/internal/demo/faults")
                        .with(faultController()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(post("/api/checkout"))
                .andExpect(status().isOk());
    }

    @Test
    void replayedRecoveryCannotClearANewlyInjectedFault() throws Exception {
        String recoveryKey = "execution-one:recover-one";
        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .with(faultController()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/runbooks/recover-connection-pool")
                        .with(runbookExecutor())
                        .header("Idempotency-Key", recoveryKey)
                        .header("X-SentinelOps-Fencing-Token", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));

        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .with(faultController()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/runbooks/recover-connection-pool")
                        .with(runbookExecutor())
                        .header("Idempotency-Key", recoveryKey)
                        .header("X-SentinelOps-Fencing-Token", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
        mockMvc.perform(post("/api/checkout"))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(post("/internal/runbooks/recover-connection-pool")
                        .with(runbookExecutor())
                        .header("Idempotency-Key", "execution-two:recover-one")
                        .header("X-SentinelOps-Fencing-Token", "1"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/checkout"))
                .andExpect(status().isOk());
    }

    @Test
    void staleFenceForAnExistingRecoveryKeyIsRejected() throws Exception {
        String recoveryKey = "execution-fenced:recover-one";
        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .with(faultController()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/runbooks/recover-connection-pool")
                        .with(runbookExecutor())
                        .header("Idempotency-Key", recoveryKey)
                        .header("X-SentinelOps-Fencing-Token", "9"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .with(faultController()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/internal/runbooks/recover-connection-pool")
                        .with(runbookExecutor())
                        .header("Idempotency-Key", recoveryKey)
                        .header("X-SentinelOps-Fencing-Token", "8"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/checkout"))
                .andExpect(status().isServiceUnavailable());
    }

    private RequestPostProcessor faultController() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_demo:fault"))
                .jwt(token -> token
                        .issuer("https://issuer.sentinelops.test")
                        .subject("demo-controller")
                        .audience(List.of("demo-service")));
    }

    private RequestPostProcessor runbookExecutor() {
        return jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_runbook:execute:checkout"))
                .jwt(token -> token
                        .issuer("https://issuer.sentinelops.test")
                        .subject("sentinelops-executor")
                        .audience(List.of("demo-service")));
    }
}
