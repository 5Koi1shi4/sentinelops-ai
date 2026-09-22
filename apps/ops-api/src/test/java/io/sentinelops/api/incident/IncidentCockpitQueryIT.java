package io.sentinelops.api.incident;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class IncidentCockpitQueryIT extends PostgresIntegrationTest {

    @Autowired private JdbcClient jdbc;
    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void returnsServiceScopedSanitizedCockpitWithStableEntityIdsAndEtag() throws Exception {
        var fixture = cockpitFixture();
        UUID historicalEvidenceId = insertHistoricalEvidence(fixture.incidentId());

        var result = mockMvc.perform(get("/api/v1/incidents/{id}", fixture.incidentId())
                        .with(observer(fixture.serviceId())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"7\""))
                .andExpect(jsonPath("$.incident.id").value(fixture.incidentId().toString()))
                .andExpect(jsonPath("$.diagnosis.id").value(fixture.proposalId().toString()))
                .andExpect(jsonPath("$.evidence[0].id").value(fixture.evidenceId().toString()))
                .andExpect(jsonPath("$.activeApproval.id").value(fixture.approvalId().toString()))
                .andExpect(jsonPath("$.latestExecution.id").value(fixture.executionId().toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("prompt-secret-marker")
                .doesNotContain("ticket-secret-marker")
                .doesNotContain("raw-provider-response-marker")
                .doesNotContain("unredacted-evidence-marker");

        mockMvc.perform(get("/api/v1/incidents/{id}", fixture.incidentId())
                        .with(observer(fixture.otherServiceId())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/incidents/{id}/timeline", fixture.incidentId())
                        .with(observer(fixture.otherServiceId())))
                .andExpect(status().isForbidden());

        var timeline = mockMvc.perform(get("/api/v1/incidents/{id}/timeline", fixture.incidentId())
                        .with(observer(fixture.serviceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].summary").value("Diagnosis completed"))
                .andExpect(jsonPath("$.items[0].traceId").value("trace-demo-01"))
                .andExpect(jsonPath("$.items[0].evidenceIds[0]")
                        .value(fixture.evidenceId().toString()))
                .andExpect(jsonPath("$.items[0].payload").doesNotExist())
                .andExpect(jsonPath("$.items[1].evidenceIds").isEmpty())
                .andReturn();
        assertThat(timeline.getResponse().getContentAsString())
                .doesNotContain("timeline-secret-marker");

        mockMvc.perform(get("/api/v1/incidents/{id}/evidence", fixture.incidentId())
                        .with(observer(fixture.otherServiceId())))
                .andExpect(status().isForbidden());
        long evidenceBefore = jdbc.sql("select count(*) from evidence_snapshot").query(Long.class).single();
        long auditsBefore = jdbc.sql("select count(*) from audit_record").query(Long.class).single();
        mockMvc.perform(get("/api/v1/incidents/{id}/evidence", fixture.incidentId())
                        .with(observer(fixture.serviceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].from").value("2026-09-23T00:00:00Z"))
                .andExpect(jsonPath("$[0].redactionCount").value(2))
                .andExpect(jsonPath("$[0].redactionRules.length()").value(2))
                .andExpect(jsonPath("$[0].redactionRules[0]").value("key-denylist"))
                .andExpect(jsonPath("$[0].redactedPayload.parameters").doesNotExist());
        var evidence = mockMvc.perform(get("/api/v1/incidents/{id}/evidence/{evidenceId}",
                        fixture.incidentId(), fixture.evidenceId()).with(observer(fixture.serviceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-23T00:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-09-23T00:15:00Z"))
                .andExpect(jsonPath("$.redactionCount").value(2))
                .andExpect(jsonPath("$.redactionRules.length()").value(2))
                .andExpect(jsonPath("$.redactionRules[0]").value("key-denylist"))
                .andExpect(jsonPath("$.redactedPayload.parameters").doesNotExist())
                .andReturn();
        assertThat(evidence.getResponse().getContentAsString())
                .doesNotContain("query-secret-marker", "parameter-secret-marker", "unredacted-evidence-marker", "unregistered-secret-marker");
        mockMvc.perform(get("/api/v1/incidents/{id}/evidence/{evidenceId}",
                        fixture.incidentId(), historicalEvidenceId).with(observer(fixture.serviceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.to").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.redactionCount").value(0))
                .andExpect(jsonPath("$.redactionRules.length()").value(0));
        UUID foreignEvidence = jdbc.sql("""
                select e.id from evidence_snapshot e join incident i on i.id=e.incident_id
                where i.service_id=:service and e.source_ref='FOREIGN-EVIDENCE'
                """).param("service", fixture.otherServiceId()).query(UUID.class).single();
        mockMvc.perform(get("/api/v1/incidents/{id}/evidence/{evidenceId}",
                        fixture.incidentId(), foreignEvidence).with(observer(fixture.serviceId())))
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("select count(*) from evidence_snapshot").query(Long.class).single()).isEqualTo(evidenceBefore);
        assertThat(jdbc.sql("select count(*) from audit_record").query(Long.class).single()).isEqualTo(auditsBefore);
        mockMvc.perform(get("/api/v1/incidents")
                        .with(observer(fixture.otherServiceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].serviceId")
                        .value(fixture.otherServiceId().toString()));
    }

    private CockpitFixture cockpitFixture() {
        UUID serviceId = jdbc.sql("select id from service_catalog where service_key = 'checkout-api'")
                .query(UUID.class)
                .single();
        UUID otherServiceId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID requesterId = jdbc.sql("select id from principal where subject = 'demo-author'")
                .query(UUID.class)
                .single();
        UUID reviewerId = jdbc.sql("select id from principal where subject = 'demo-reviewer'")
                .query(UUID.class)
                .single();
        UUID runbookVersionId = jdbc.sql("""
                        select rv.id
                        from runbook_version rv
                        join runbook r on r.id = rv.runbook_id
                        where r.runbook_key = 'RB-DB-POOL-03'
                          and rv.version_number = 1
                        """)
                .query(UUID.class)
                .single();
        var now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.sql("""
                        insert into service_catalog(
                          id, service_key, display_name, owner_team, slo_config,
                          data_source_refs, execution_target_aliases, created_at, updated_at
                        ) values (
                          :id, :serviceKey, 'Other service', 'other-team', '{}'::jsonb,
                          '{}'::jsonb, '{}'::jsonb, :now, :now
                        )
                        """)
                .param("id", otherServiceId)
                .param("serviceKey", "other-service-" + otherServiceId)
                .param("now", now)
                .update();
        jdbc.sql("""
                        insert into incident(
                          id, service_id, fingerprint, title, severity, status,
                          version, next_event_seq, occurrence_count, opened_at, updated_at
                        ) values (
                          :id, :serviceId, :fingerprint, '数据库连接池耗尽', 'sev1',
                          'executing', 7, 1, 4, :openedAt, :now
                        )
                        """)
                .param("id", incidentId)
                .param("serviceId", serviceId)
                .param("fingerprint", "cockpit-" + incidentId)
                .param("openedAt", now.minusMinutes(12))
                .param("now", now)
                .update();
        jdbc.sql("""
                        insert into diagnosis_run(
                          id, incident_id, requested_by_principal_id, incident_version,
                          engine_type, status, model_provider, model_name, prompt_version,
                          input_hash, failure_code, started_at, completed_at
                        ) values (
                          :id, :incidentId, :requesterId, 2, 'model', 'succeeded',
                          'provider', 'model', 'prompt-secret-marker', :inputHash,
                          'raw-provider-response-marker', :startedAt, :completedAt
                        )
                        """)
                .param("id", runId)
                .param("incidentId", incidentId)
                .param("requesterId", requesterId)
                .param("inputHash", "input-" + incidentId)
                .param("startedAt", now.minusMinutes(10))
                .param("completedAt", now.minusMinutes(9))
                .update();
        jdbc.sql("""
                        insert into evidence_snapshot(
                          id, incident_id, diagnosis_run_id, source_type, source_ref,
                          query_spec, redacted_payload, content_hash, captured_at, truncated
                        ) values (
                          :id, :incidentId, :runId, 'metric', 'E-12',
                          '{"query":"unredacted-evidence-marker","token":"query-secret-marker"}'::jsonb,
                          '{"summary":"连接池等待线程持续上升","from":"2026-09-23T00:00:00Z","to":"2026-09-23T00:15:00Z","parameters":{"token":"parameter-secret-marker"},"redaction":{"count":2,"rules":["key-denylist","string-length","unregistered-secret-marker"]}}'::jsonb,
                          :contentHash, :capturedAt, false
                        )
                        """)
                .param("id", evidenceId)
                .param("incidentId", incidentId)
                .param("runId", runId)
                .param("contentHash", "evidence-" + incidentId)
                .param("capturedAt", now.minusMinutes(10))
                .update();
        jdbc.sql("""
                        insert into diagnosis_proposal(
                          id, diagnosis_run_id, incident_id, runbook_version_id, summary,
                          proposal_payload, proposal_hash, risk_level, created_at
                        ) values (
                          :id, :runId, :incidentId, :runbookVersionId, '数据库连接池耗尽',
                          cast(:payload as jsonb), :proposalHash, 'r1', :createdAt
                        )
                        """)
                .param("id", proposalId)
                .param("runId", runId)
                .param("incidentId", incidentId)
                .param("runbookVersionId", runbookVersionId)
                .param("payload", """
                        {
                          "hypotheses":[{"rank":1,"statement":"连接池已耗尽","confidence":0.91,"evidenceRefs":["%s"]}],
                          "missingEvidence":[],
                          "parameters":{"replicas":1},
                          "expectedVerification":{"probe":"demo-http","successThreshold":1,"attempts":3,"intervalSeconds":2},
                          "rawPrompt":"prompt-secret-marker",
                          "rawProviderResponse":"raw-provider-response-marker"
                        }
                        """.formatted(evidenceId))
                .param("proposalHash", "proposal-hash-" + incidentId)
                .param("createdAt", now.minusMinutes(9))
                .update();
        jdbc.sql("""
                        insert into diagnosis_proposal_evidence(proposal_id, evidence_snapshot_id)
                        values (:proposalId, :evidenceId)
                        """)
                .param("proposalId", proposalId)
                .param("evidenceId", evidenceId)
                .update();
        jdbc.sql("""
                        insert into incident_event(
                          id, incident_id, seq_no, event_type, actor_type, actor_id,
                          source, source_event_id, payload, occurred_at
                        ) values (
                          :id, :incidentId, 1, 'diagnosis_succeeded', 'user', 'operator-demo',
                          null, null, cast(:payload as jsonb), :occurredAt
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("incidentId", incidentId)
                .param("payload", """
                        {
                          "proposalId":"%s",
                          "traceId":"trace-demo-01",
                          "secret":"timeline-secret-marker"
                        }
                        """.formatted(proposalId))
                .param("occurredAt", now.minusMinutes(9))
                .update();
        jdbc.sql("""
                        insert into approval_request(
                          id, incident_id, proposal_id, proposal_hash, requester_principal_id,
                          policy_version, required_approvals, independent_approver_required,
                          status, resource_version, target_alias, created_at, expires_at, decided_at
                        ) values (
                          :id, :incidentId, :proposalId, :proposalHash, :requesterId,
                          'stage1-v1', 1, true, 'approved', 1, 'demo-checkout',
                          :createdAt, :expiresAt, :decidedAt
                        )
                        """)
                .param("id", approvalId)
                .param("incidentId", incidentId)
                .param("proposalId", proposalId)
                .param("proposalHash", "proposal-hash-" + incidentId)
                .param("requesterId", requesterId)
                .param("createdAt", now.minusMinutes(8))
                .param("expiresAt", now.plusMinutes(8))
                .param("decidedAt", now.minusMinutes(7))
                .update();
        jdbc.sql("""
                        insert into approval_decision(
                          id, approval_request_id, reviewer_principal_id, decision,
                          comment, proposal_hash, decided_at
                        ) values (
                          :id, :approvalId, :reviewerId, 'approve', '范围已核验',
                          :proposalHash, :decidedAt
                        )
                        """)
                .param("id", decisionId)
                .param("approvalId", approvalId)
                .param("reviewerId", reviewerId)
                .param("proposalHash", "proposal-hash-" + incidentId)
                .param("decidedAt", now.minusMinutes(7))
                .update();
        jdbc.sql("""
                        insert into execution(
                          id, incident_id, proposal_id, approval_request_id, status,
                          idempotency_key, ticket_jti, ticket_issued_at, claim_attempt_key,
                          claimed_by, lease_until, fencing_token, target_alias,
                          created_at, updated_at, started_at
                        ) values (
                          :id, :incidentId, :proposalId, :approvalId, 'running',
                          :idempotencyKey, 'ticket-secret-marker', :ticketIssuedAt, :claimAttemptKey,
                          'executor-demo', :leaseUntil, 4, 'demo-checkout',
                          :createdAt, :updatedAt, :startedAt
                        )
                        """)
                .param("id", executionId)
                .param("incidentId", incidentId)
                .param("proposalId", proposalId)
                .param("approvalId", approvalId)
                .param("idempotencyKey", "cockpit-execution-" + incidentId)
                .param("ticketIssuedAt", now.minusMinutes(6))
                .param("claimAttemptKey", "cockpit-claim-" + incidentId)
                .param("leaseUntil", now.plusMinutes(1))
                .param("createdAt", now.minusMinutes(7))
                .param("updatedAt", now.minusMinutes(6))
                .param("startedAt", now.minusMinutes(6))
                .update();

        UUID foreignIncidentId = UUID.randomUUID();
        UUID foreignRunId = UUID.randomUUID();
        UUID foreignProposalId = UUID.randomUUID();
        UUID foreignEvidenceId = UUID.randomUUID();
        jdbc.sql("""
                        insert into incident(
                          id, service_id, fingerprint, title, severity, status,
                          version, next_event_seq, occurrence_count, opened_at, updated_at
                        ) values (
                          :id, :serviceId, :fingerprint, 'Foreign incident', 'sev2',
                          'triaging', 1, 1, 1, :openedAt, :updatedAt
                        )
                        """)
                .param("id", foreignIncidentId)
                .param("serviceId", otherServiceId)
                .param("fingerprint", "foreign-" + foreignIncidentId)
                .param("openedAt", now.minusMinutes(5))
                .param("updatedAt", now.minusMinutes(5))
                .update();
        jdbc.sql("""
                        insert into diagnosis_run(
                          id, incident_id, requested_by_principal_id, incident_version,
                          engine_type, status, prompt_version, input_hash,
                          started_at, completed_at
                        ) values (
                          :id, :incidentId, :requesterId, 1, 'deterministic', 'succeeded',
                          'foreign-v1', :inputHash, :startedAt, :completedAt
                        )
                        """)
                .param("id", foreignRunId)
                .param("incidentId", foreignIncidentId)
                .param("requesterId", requesterId)
                .param("inputHash", "foreign-input-" + foreignIncidentId)
                .param("startedAt", now.minusMinutes(4))
                .param("completedAt", now.minusMinutes(4))
                .update();
        jdbc.sql("""
                        insert into evidence_snapshot(
                          id, incident_id, diagnosis_run_id, source_type, source_ref,
                          query_spec, redacted_payload, content_hash, captured_at, truncated
                        ) values (
                          :id, :incidentId, :runId, 'metric', 'FOREIGN-EVIDENCE',
                          '{}'::jsonb, '{}'::jsonb, :contentHash, :capturedAt, false
                        )
                        """)
                .param("id", foreignEvidenceId)
                .param("incidentId", foreignIncidentId)
                .param("runId", foreignRunId)
                .param("contentHash", "foreign-evidence-" + foreignIncidentId)
                .param("capturedAt", now.minusMinutes(4))
                .update();
        jdbc.sql("""
                        insert into diagnosis_proposal(
                          id, diagnosis_run_id, incident_id, runbook_version_id, summary,
                          proposal_payload, proposal_hash, risk_level, created_at
                        ) values (
                          :id, :runId, :incidentId, :runbookVersionId, 'Foreign proposal',
                          '{}'::jsonb, :proposalHash, 'r1', :createdAt
                        )
                        """)
                .param("id", foreignProposalId)
                .param("runId", foreignRunId)
                .param("incidentId", foreignIncidentId)
                .param("runbookVersionId", runbookVersionId)
                .param("proposalHash", "foreign-proposal-" + foreignIncidentId)
                .param("createdAt", now.minusMinutes(4))
                .update();
        jdbc.sql("""
                        insert into diagnosis_proposal_evidence(proposal_id, evidence_snapshot_id)
                        values (:proposalId, :evidenceId)
                        """)
                .param("proposalId", foreignProposalId)
                .param("evidenceId", foreignEvidenceId)
                .update();
        jdbc.sql("""
                        insert into incident_event(
                          id, incident_id, seq_no, event_type, actor_type, actor_id,
                          source, source_event_id, payload, occurred_at
                        ) values (
                          :id, :incidentId, 2, 'diagnosis_succeeded', 'integration',
                          'untrusted-alert', 'alertmanager', 'foreign-proposal-probe',
                          cast(:payload as jsonb), :occurredAt
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("incidentId", incidentId)
                .param("payload", """
                        {"proposalId":"%s"}
                        """.formatted(foreignProposalId))
                .param("occurredAt", now.minusMinutes(3))
                .update();

        return new CockpitFixture(
                incidentId,
                serviceId,
                otherServiceId,
                proposalId,
                evidenceId,
                approvalId,
                executionId);
    }

    private UUID insertHistoricalEvidence(UUID incidentId) {
        UUID id = UUID.randomUUID();
        UUID runId = jdbc.sql("select id from diagnosis_run where incident_id=:incident order by started_at desc limit 1")
                .param("incident", incidentId).query(UUID.class).single();
        jdbc.sql("""
                        insert into evidence_snapshot(
                          id, incident_id, diagnosis_run_id, source_type, source_ref,
                          query_spec, redacted_payload, content_hash, captured_at, truncated
                        ) values (
                          :id, :incident, :run, 'loki', 'HISTORICAL',
                          '{"query":"older registered query"}'::jsonb,
                          '{"summary":"older evidence"}'::jsonb,
                          :hash, clock_timestamp(), false
                        )
                        """)
                .param("id", id).param("incident", incidentId).param("run", runId)
                .param("hash", "historical-evidence-" + id).update();
        return id;
    }

    private RequestPostProcessor observer(UUID serviceId) {
        return jwt().authorities(
                        new SimpleGrantedAuthority(API_AUTHORITY),
                        new SimpleGrantedAuthority("ROLE_OBSERVER"))
                .jwt(token -> token
                        .issuer("https://issuer.sentinelops.test")
                        .subject("observer-" + serviceId)
                        .audience(List.of("sentinelops-api"))
                        .claim("realm_access", Map.of("roles", List.of("observer")))
                        .claim("service_ids", List.of(serviceId.toString())));
    }

    private record CockpitFixture(
            UUID incidentId,
            UUID serviceId,
            UUID otherServiceId,
            UUID proposalId,
            UUID evidenceId,
            UUID approvalId,
            UUID executionId) {}
}
