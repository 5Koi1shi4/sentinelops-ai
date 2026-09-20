package io.sentinelops.api.identity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.incident.application.IncidentSummary;
import io.sentinelops.api.approval.application.ApprovalApplicationService.ApprovalView;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposal;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

class AuthorizationIT extends PostgresIntegrationTest {

    @Autowired private IncidentApplicationService incidents;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void observerCannotStartDiagnosis() throws Exception {
        var incident = diagnosableIncident("observer");

        mockMvc.perform(post("/api/v1/incidents/{id}/diagnosis-runs", incident.id())
                        .with(principal("observer-subject", "observer", incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "observer-diagnosis-" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyHealthIsAnonymousOutsideTheDemoProfile() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/integrations/alertmanager/webhook")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onCallOperatorCanStartDiagnosisForAnAssignedService() throws Exception {
        var incident = diagnosableIncident("operator");

        mockMvc.perform(post("/api/v1/incidents/{id}/diagnosis-runs", incident.id())
                        .with(principal(
                                "operator-subject", "on_call_operator", incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "operator-diagnosis-" + UUID.randomUUID()))
                .andExpect(status().isCreated());
    }

    @Test
    void sreApproverCanDecideOnlyForAnAssignedService() throws Exception {
        var incident = diagnosableIncident("approval-scope");
        String operatorSubject = "operator-" + UUID.randomUUID();
        var diagnosisResult = mockMvc.perform(post(
                                "/api/v1/incidents/{id}/diagnosis-runs", incident.id())
                        .with(principal(
                                operatorSubject, "on_call_operator", incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "scoped-diagnosis-" + UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andReturn();
        var proposal = objectMapper.readValue(
                diagnosisResult.getResponse().getContentAsString(), DiagnosisProposal.class);

        var approvalResult = mockMvc.perform(post(
                                "/api/v1/incidents/{id}/approval-requests", incident.id())
                        .with(principal(
                                operatorSubject, "on_call_operator", incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "scoped-request-" + UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("proposalId", proposal.id()))))
                .andExpect(status().isCreated())
                .andReturn();
        var approval = objectMapper.readValue(
                approvalResult.getResponse().getContentAsString(), ApprovalView.class);
        String approvalIncidentEtag = approvalResult.getResponse().getHeader(HttpHeaders.ETAG);
        var decision = Map.of(
                "decision", "approve",
                "comment", "scope checked",
                "proposalHash", proposal.proposalHash());

        mockMvc.perform(post(
                                "/api/v1/approval-requests/{id}/decisions", approval.id())
                        .with(principal(
                                "wrong-scope-approver",
                                "sre_approver",
                                UUID.randomUUID()))
                        .header(HttpHeaders.IF_MATCH, approvalIncidentEtag)
                        .header("Idempotency-Key", "wrong-scope-" + UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decision)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(
                                "/api/v1/approval-requests/{id}/decisions", approval.id())
                        .with(principal(
                                "assigned-approver",
                                "sre_approver",
                                incident.serviceId()))
                        .header(HttpHeaders.IF_MATCH, approvalIncidentEtag)
                        .header("Idempotency-Key", "assigned-scope-" + UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decision)))
                .andExpect(status().isOk());
    }

    private IncidentSummary diagnosableIncident(String prefix) {
        String suffix = UUID.randomUUID().toString();
        var incident = incidents.ingest(new AlertEnvelope(
                "alertmanager",
                prefix + "-event-" + suffix,
                "checkout-api",
                prefix + "-fingerprint-" + suffix,
                "Database pool saturation",
                "sev1",
                AlertEnvelope.AlertStatus.FIRING,
                objectMapper.createObjectNode().put("status", "firing")));
        insertEvidence(
                incident.id(),
                "metric",
                "promql:db_pool_pending",
                objectMapper.createObjectNode().put("db_pool_pending", 4),
                "metric-" + suffix);
        insertEvidence(
                incident.id(),
                "log",
                "loki:acquire-timeout",
                objectMapper.createObjectNode().put("acquire_timeout_count", 9),
                "log-" + suffix);
        return incident;
    }

    private void insertEvidence(
            UUID incidentId,
            String sourceType,
            String sourceRef,
            tools.jackson.databind.JsonNode payload,
            String contentHash) {
        jdbc.sql("""
                        insert into evidence_snapshot(
                          id, incident_id, source_type, source_ref, query_spec,
                          redacted_payload, content_hash, captured_at, truncated
                        ) values (
                          :id, :incidentId, :sourceType, :sourceRef, '{}'::jsonb,
                          cast(:payload as jsonb), :contentHash, :capturedAt, false
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("incidentId", incidentId)
                .param("sourceType", sourceType)
                .param("sourceRef", sourceRef)
                .param("payload", objectMapper.writeValueAsString(payload))
                .param("contentHash", contentHash)
                .param("capturedAt", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
    }

    private RequestPostProcessor principal(String subject, String role, UUID serviceId) {
        return jwt().authorities(
                        new SimpleGrantedAuthority(API_AUTHORITY),
                        new SimpleGrantedAuthority(
                                "ROLE_" + role.toUpperCase(java.util.Locale.ROOT)))
                .jwt(token -> token
                        .issuer("https://issuer.sentinelops.test")
                        .subject(subject)
                        .audience(List.of("sentinelops-api"))
                .claim("realm_access", Map.of("roles", List.of(role)))
                .claim("service_ids", List.of(serviceId.toString())));
    }
}
