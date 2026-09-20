package io.sentinelops.demo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
    "sentinelops.demo-mode=false",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost.invalid/demo-jwks",
    "sentinelops.security.issuer=https://issuer.sentinelops.test",
    "sentinelops.security.audience=demo-service"
})
class DemoFaultDisabledIT {

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void faultEndpointDoesNotExistOutsideDemoMode() throws Exception {
        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("SCOPE_demo:fault"))
                                .jwt(token -> token
                                        .issuer("https://issuer.sentinelops.test")
                                        .subject("demo-controller")
                                        .audience(List.of("demo-service")))))
                .andExpect(status().isNotFound());
    }
}
