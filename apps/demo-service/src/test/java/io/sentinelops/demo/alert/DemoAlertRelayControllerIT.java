package io.sentinelops.demo.alert;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
    "sentinelops.demo-mode=true",
    "sentinelops.demo-alert-relay-mode=true",
    "sentinelops.demo-alert-relay-url=http://127.0.0.1:1/webhook",
    "sentinelops.demo-alert-relay-secret=demo-only-webhook-test-key-over-32-bytes",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost.invalid/demo-jwks"
})
class DemoAlertRelayControllerIT {
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void privateRelayRouteIsAvailableOnlyInExplicitDemoRelayModeAndBoundsBody() throws Exception {
        mvc.perform(post("/internal/demo/alertmanager-relay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x".repeat(1_048_577)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/internal/demo/alertmanager-relay")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadGateway());
    }
}
