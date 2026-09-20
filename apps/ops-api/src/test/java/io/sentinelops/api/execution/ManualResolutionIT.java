package io.sentinelops.api.execution;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

class ManualResolutionIT extends ExecutionFixtureSupport {

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
    void manualResolutionStartsObjectiveVerificationAndIsIdempotent() throws Exception {
        var fixture = approvedFixture();
        jdbc.sql("update incident set status = 'triaging' where id = :id")
                .param("id", fixture.incidentId())
                .update();
        String body = objectMapper.writeValueAsString(Map.of(
                "reason", "Operator observed the alert clear and requests objective verification"));

        var first = mockMvc.perform(post(
                                "/api/v1/incidents/{id}/resolve",
                                fixture.incidentId())
                        .with(operator(fixture.serviceId(), "manual-operator"))
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .header("Idempotency-Key", "manual-resolve-" + fixture.incidentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("verifying"))
                .andReturn();
        var replay = mockMvc.perform(post(
                                "/api/v1/incidents/{id}/resolve",
                                fixture.incidentId())
                        .with(operator(fixture.serviceId(), "manual-operator"))
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .header("Idempotency-Key", "manual-resolve-" + fixture.incidentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(first.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"4\"");
        assertThat(jdbc.sql("select status from incident where id = :id")
                        .param("id", fixture.incidentId())
                        .query(String.class)
                        .single())
                .isEqualTo("verifying");
        assertThat(jdbc.sql("""
                                select payload ->> 'reason'
                                from incident_event
                                where incident_id = :incidentId
                                  and event_type = 'manual_verification_requested'
                                """)
                        .param("incidentId", fixture.incidentId())
                        .query(String.class)
                        .single())
                .contains("objective verification");
        assertThat(jdbc.sql("""
                                select metadata ->> 'reason'
                                from audit_record
                                where resource_id = :incidentId
                                  and action = 'incident.manual_verification_requested'
                                """)
                        .param("incidentId", fixture.incidentId().toString())
                        .query(String.class)
                        .single())
                .contains("objective verification");
        assertThat(jdbc.sql("""
                                select runbook_version_id
                                from verification_cycle
                                where incident_id = :incidentId
                                  and incident_version = 4
                                """)
                        .param("incidentId", fixture.incidentId())
                        .query(UUID.class)
                        .single())
                .isEqualTo(fixture.runbookVersionId());
    }

    @Test
    void reasonHeadersScopeAndPublishedVerificationPolicyAreMandatory() throws Exception {
        var fixture = approvedFixture();
        jdbc.sql("update incident set status = 'triaging' where id = :id")
                .param("id", fixture.incidentId())
                .update();
        String body = "{\"reason\":\"verify recovery\"}";

        mockMvc.perform(post("/api/v1/incidents/{id}/resolve", fixture.incidentId())
                        .with(operator(fixture.serviceId(), "manual-operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/incidents/{id}/resolve", fixture.incidentId())
                        .with(operator(UUID.randomUUID(), "outside-operator"))
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .header("Idempotency-Key", "outside-scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        UUID noPolicyIncident = insertIncidentWithoutPolicy(fixture.serviceId());
        mockMvc.perform(post("/api/v1/incidents/{id}/resolve", noPolicyIncident)
                        .with(operator(fixture.serviceId(), "manual-operator"))
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", "no-policy-" + noPolicyIncident)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NO_VERIFICATION_POLICY"));
    }

    private UUID insertIncidentWithoutPolicy(UUID serviceId) {
        UUID incidentId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        insert into incident(
                          id, service_id, fingerprint, title, severity, status,
                          version, next_event_seq, occurrence_count, opened_at, updated_at
                        ) values (
                          :id, :serviceId, :fingerprint, 'No policy', 'sev3', 'triaging',
                          1, 1, 1, :now, :now
                        )
                        """)
                .param("id", incidentId)
                .param("serviceId", serviceId)
                .param("fingerprint", "no-policy-" + incidentId)
                .param("now", now)
                .update();
        return incidentId;
    }

    private RequestPostProcessor operator(UUID serviceId, String subject) {
        return jwt().authorities(
                        new SimpleGrantedAuthority(API_AUTHORITY),
                        new SimpleGrantedAuthority("ROLE_ON_CALL_OPERATOR"))
                .jwt(token -> token
                        .issuer("https://issuer.sentinelops.test")
                        .subject(subject)
                        .audience(List.of("sentinelops-api"))
                        .claim("realm_access", Map.of("roles", List.of("on_call_operator")))
                        .claim("service_ids", List.of(serviceId.toString())));
    }
}
