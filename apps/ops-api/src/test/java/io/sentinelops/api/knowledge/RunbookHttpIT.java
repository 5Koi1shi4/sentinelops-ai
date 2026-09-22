package io.sentinelops.api.knowledge;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static org.assertj.core.api.Assertions.*;
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
import tools.jackson.databind.node.ObjectNode;

class RunbookHttpIT extends PostgresIntegrationTest {
    private static final String SERVICE = "0199a000-0000-7000-8000-000000000001";
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    MockMvc mvc;
    @BeforeEach void mvc() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void authorReviewPublishSearchWorkflowUsesEtagAndServiceScope() throws Exception {
        String key = "RB." + UUID.randomUUID();
        var result = mvc.perform(post("/api/v1/runbooks/{key}/versions", key).with(actor("author", "RUNBOOK_ADMIN", true))
                .header("Idempotency-Key", "k".repeat(200)).contentType(MediaType.APPLICATION_JSON).content(body(key).toString()))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\"")).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).path("id").asString();
        mvc.perform(post("/api/v1/runbook-versions/{id}/review", id).with(actor("reviewer", "RUNBOOK_ADMIN", true))
                .header("Idempotency-Key", UUID.randomUUID()).header("If-Match", "\"0\""))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        mvc.perform(post("/api/v1/runbook-versions/{id}/publish", id).with(actor("author", "RUNBOOK_ADMIN", true))
                .header("Idempotency-Key", UUID.randomUUID()).header("If-Match", "\"0\""))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post("/api/v1/runbook-versions/{id}/publish", id).with(actor("author", "RUNBOOK_ADMIN", true))
                .header("Idempotency-Key", UUID.randomUUID()).header("If-Match", "\"1\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lifecycle").value("published"));
        mvc.perform(get("/api/v1/runbooks/search").param("serviceId", SERVICE).param("query", "connection pool timeout")
                .with(actor("observer", "OBSERVER", true))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("runbook")).andExpect(jsonPath("$[0].runbookKey").value(key));
        mvc.perform(get("/api/v1/runbooks/search").param("serviceId", SERVICE).param("query", "pool")
                .with(actor("outsider", "OBSERVER", false))).andExpect(status().isForbidden());
    }

    @Test void rejectsUnknownFieldsMissingHeadersAndWrongRole() throws Exception {
        String key = "RB-" + UUID.randomUUID();
        var invalid = body(key).put("authorPrincipalId", UUID.randomUUID().toString());
        mvc.perform(post("/api/v1/runbooks/{key}/versions", key).with(actor("author", "RUNBOOK_ADMIN", true))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(invalid.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/runbooks/{key}/versions", key).with(actor("author", "RUNBOOK_ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON).content(body(key).toString())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/runbooks/{key}/versions", key).with(actor("observer", "OBSERVER", true))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body(key).toString()))
                .andExpect(status().isForbidden());
    }

    @Test void draftListGetAndDiffRemainAdminOnly() throws Exception {
        String key = "RB-" + UUID.randomUUID();
        var result = mvc.perform(post("/api/v1/runbooks/{key}/versions", key).with(actor("author", "RUNBOOK_ADMIN", true))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body(key).toString()))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).path("id").asString();
        mvc.perform(get("/api/v1/runbooks/{key}/versions", key).with(actor("author", "RUNBOOK_ADMIN", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/v1/runbook-versions/{id}", id).with(actor("observer", "OBSERVER", true)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/runbook-versions/{id}/diff", id).param("otherVersionId", id)
                .with(actor("author", "RUNBOOK_ADMIN", true))).andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionChanged").value(false));
        mvc.perform(get("/api/v1/runbook-versions/{id}/diff", id)
                .param("otherVersionId", "0199a000-0000-7000-8000-000000000005")
                .with(actor("author", "RUNBOOK_ADMIN", true))).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/runbook-versions/{id}", id).with(actor("author", "RUNBOOK_ADMIN", true))
                .header("Idempotency-Key", UUID.randomUUID()).header("If-Match", "W/\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(json.createObjectNode()
                        .set("definition", body(key).get("definition")).put("markdown", "new text").toString()))
                .andExpect(status().isBadRequest());
    }

    @Test void unicodeLengthsMatchThePublishedContractAndMalformedTextIsRejected() throws Exception {
        String key = "RB-" + UUID.randomUUID();
        var content = body(key).put("markdown", "🙂".repeat(60001));
        mvc.perform(post("/api/v1/runbooks/{key}/versions", key).with(actor("author", "RUNBOOK_ADMIN", true))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(content.toString()))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/runbooks/search").param("serviceId", SERVICE).param("query", "🙂".repeat(501))
                .with(actor("observer", "OBSERVER", true))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/runbooks/search").param("serviceId", SERVICE).param("query", "broken\uD800text")
                .with(actor("observer", "OBSERVER", true))).andExpect(status().isBadRequest());
    }

    @Test void unknownAuthorizedServiceReturnsNotFoundForSearchAndCreation() throws Exception {
        String missing = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/runbooks/search").param("serviceId", missing).param("query", "pool")
                .with(actor("admin", "PLATFORM_ADMIN", false))).andExpect(status().isNotFound());
        String key = "RB-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/runbooks/{key}/versions", key).with(actor("admin", "PLATFORM_ADMIN", false))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(body(key).put("serviceId", missing).toString())).andExpect(status().isNotFound());
    }

    ObjectNode body(String key) {
        var definition = (ObjectNode) json.readTree(jdbc.sql("select definition::text from runbook_version where id='0199a000-0000-7000-8000-000000000005'")
                .query(String.class).single());
        definition.put("runbookKey", key);
        return json.createObjectNode().put("serviceId", SERVICE).put("displayName", "Pool recovery").put("ownerTeam", "demo-sre")
                .put("markdown", "connection pool timeout").set("definition", definition);
    }
    RequestPostProcessor actor(String subject, String role, boolean scoped) {
        return jwt().authorities(new SimpleGrantedAuthority(API_AUTHORITY), new SimpleGrantedAuthority("ROLE_" + role))
                .jwt(token -> token.issuer("https://issuer.sentinelops.test").subject(subject)
                        .claim("realm_access", Map.of("roles", List.of(role)))
                        .claim("service_ids", scoped ? List.of(SERVICE) : List.of()));
    }
}
