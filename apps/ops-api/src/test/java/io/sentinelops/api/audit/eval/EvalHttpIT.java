package io.sentinelops.api.audit.eval;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

class EvalHttpIT extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;
    @BeforeEach void setup() { mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void adminCanRunReadAndReplayWhileUntrustedProviderOverrideIsRejected() throws Exception {
        String key=UUID.randomUUID().toString();
        String body="{\"datasetKey\":\"incidents-v1\",\"baselineRunId\":null}";
        var response=mvc.perform(post("/api/v1/eval-runs").with(actor("PLATFORM_ADMIN"))
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.results.length()").value(12)).andReturn();
        String id=mapper.readTree(response.getResponse().getContentAsString()).path("id").asString();
        mvc.perform(get("/api/v1/eval-runs/{id}",id).with(actor("PLATFORM_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/v1/eval-runs").with(actor("PLATFORM_ADMIN"))
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/v1/eval-runs/{id}",id).with(actor("ON_CALL_OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/eval-runs").with(actor("PLATFORM_ADMIN"))
                .header("Idempotency-Key","invalid-config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasetKey\":\"incidents-v1\",\"baselineRunId\":null,\"endpoint\":\"http://private.invalid\"}"))
                .andExpect(status().isBadRequest());
    }
    private RequestPostProcessor actor(String role) {
        return jwt().jwt(token->token.issuer("https://issuer.sentinelops.test").subject("eval-http")
                .claim("realm_access",Map.of("roles",List.of(role))))
                .authorities(new SimpleGrantedAuthority(API_AUTHORITY),new SimpleGrantedAuthority("ROLE_"+role));
    }
}
