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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

class EvalHttpIT extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
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

    @Test void evalRunSummariesAreAdminOnlyUuidV7KeysetPagesWithoutFixtureOrConfigData() throws Exception {
        String body = "{\"datasetKey\":\"incidents-v1\",\"baselineRunId\":null}";
        var first = mvc.perform(post("/api/v1/eval-runs").with(actor("PLATFORM_ADMIN"))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        var second = mvc.perform(post("/api/v1/eval-runs").with(actor("PLATFORM_ADMIN"))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String firstId = mapper.readTree(first.getResponse().getContentAsString()).path("id").asString();
        String secondId = mapper.readTree(second.getResponse().getContentAsString()).path("id").asString();
        long runsBefore = jdbc.sql("select count(*) from eval_run").query(Long.class).single();
        long auditsBefore = jdbc.sql("select count(*) from audit_record").query(Long.class).single();

        var newest = mvc.perform(get("/api/v1/eval-runs").param("limit", "1").with(actor("PLATFORM_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(secondId)).andExpect(jsonPath("$[0].aggregateMetrics").exists())
                .andExpect(jsonPath("$[0].status").value("completed"))
                .andExpect(jsonPath("$[0].provider").isNotEmpty())
                .andExpect(jsonPath("$[0].modelName").isNotEmpty())
                .andExpect(jsonPath("$[0].datasetId").isNotEmpty())
                .andExpect(jsonPath("$[0].releaseAllowed").exists())
                .andExpect(jsonPath("$[0].startedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].completedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].runConfig").doesNotExist()).andExpect(jsonPath("$[0].results").doesNotExist())
                .andExpect(jsonPath("$[0].datasetChecksum").doesNotExist()).andReturn();
        mvc.perform(get("/api/v1/eval-runs").param("beforeId", secondId).param("limit", "1")
                .with(actor("PLATFORM_ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(firstId));
        mvc.perform(get("/api/v1/eval-runs").param("limit", "20").with(actor("ON_CALL_OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/eval-runs").param("limit", "0").with(actor("PLATFORM_ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/eval-runs").param("beforeId", "not-a-uuid").with(actor("PLATFORM_ADMIN")))
                .andExpect(status().isBadRequest());
        String summary = newest.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(summary).doesNotContain("inputFixture", "prompt-secret-marker", "rawProviderResponse");
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("select count(*) from eval_run").query(Long.class).single()).isEqualTo(runsBefore);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("select count(*) from audit_record").query(Long.class).single()).isEqualTo(auditsBefore);
    }
    private RequestPostProcessor actor(String role) {
        return jwt().jwt(token->token.issuer("https://issuer.sentinelops.test").subject("eval-http")
                .claim("realm_access",Map.of("roles",List.of(role))))
                .authorities(new SimpleGrantedAuthority(API_AUTHORITY),new SimpleGrantedAuthority("ROLE_"+role));
    }
}
