package io.sentinelops.api.servicecatalog;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sentinelops.api.support.PostgresIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

class ServiceCatalogHttpIT extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void listsOnlyAuthorizedServicesAndPaginatesByServiceKeyWithoutWriting() throws Exception {
        String prefix = "task6-services-" + UUID.randomUUID();
        UUID first = addService(prefix + "-a", "Service A", "team-a");
        addService(prefix + "-b", "Service B", "team-b");
        long servicesBefore = jdbc.sql("select count(*) from service_catalog").query(Long.class).single();
        long auditBefore = jdbc.sql("select count(*) from audit_record").query(Long.class).single();

        var scoped = mvc.perform(get("/api/v1/services").param("afterKey", "").param("limit", "100")
                        .with(actor("OBSERVER", List.of(first))))
                .andExpect(status().isOk()).andReturn();
        var scopedRows = json.readTree(scoped.getResponse().getContentAsString());
        assertThat(scopedRows.size()).isEqualTo(1);
        assertThat(scopedRows.get(0).path("serviceKey").asString()).isEqualTo(prefix + "-a");
        assertThat(scopedRows.get(0).path("displayName").asString()).isEqualTo("Service A");
        assertThat(scopedRows.get(0).path("ownerTeam").asString()).isEqualTo("team-a");

        var pageOne = mvc.perform(get("/api/v1/services").param("afterKey", prefix).param("limit", "1")
                        .with(actor("PLATFORM_ADMIN", List.of())))
                .andExpect(status().isOk()).andReturn();
        String cursor = json.readTree(pageOne.getResponse().getContentAsString()).get(0).path("serviceKey").asString();
        var pageTwo = mvc.perform(get("/api/v1/services").param("afterKey", cursor).param("limit", "1")
                        .with(actor("PLATFORM_ADMIN", List.of())))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(pageTwo.getResponse().getContentAsString()).get(0).path("serviceKey").asString())
                .isEqualTo(prefix + "-b");
        mvc.perform(get("/api/v1/services").param("limit", "0").with(actor("PLATFORM_ADMIN", List.of())))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("select count(*) from service_catalog").query(Long.class).single()).isEqualTo(servicesBefore);
        assertThat(jdbc.sql("select count(*) from audit_record").query(Long.class).single()).isEqualTo(auditBefore);
    }

    private UUID addService(String key, String name, String team) {
        UUID id = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                insert into service_catalog(id,service_key,display_name,owner_team,slo_config,data_source_refs,
                    execution_target_aliases,created_at,updated_at)
                values(:id,:key,:name,:team,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,:now,:now)
                """).param("id", id).param("key", key).param("name", name).param("team", team)
                .param("now", now).update();
        return id;
    }

    private RequestPostProcessor actor(String role, List<UUID> serviceIds) {
        return jwt().authorities(new SimpleGrantedAuthority(API_AUTHORITY), new SimpleGrantedAuthority("ROLE_" + role))
                .jwt(token -> token.issuer("https://issuer.sentinelops.test").subject("services-" + role)
                        .claim("realm_access", Map.of("roles", List.of(role)))
                        .claim("service_ids", serviceIds.stream().map(UUID::toString).toList()));
    }
}
