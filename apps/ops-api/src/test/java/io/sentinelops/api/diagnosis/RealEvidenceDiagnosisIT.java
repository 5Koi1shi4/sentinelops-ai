package io.sentinelops.api.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sentinelops.api.diagnosis.domain.DiagnosisProposal;
import io.sentinelops.api.diagnosis.application.model.ModelGateway;
import io.sentinelops.api.audit.eval.EvalApplicationService;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.AlertEvidenceCollector;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.incident.application.evidence.EvidenceCaptureService;
import io.sentinelops.api.incident.application.evidence.EvidenceBudget;
import io.sentinelops.api.incident.application.evidence.EvidenceQuery;
import io.sentinelops.api.incident.application.evidence.EvidenceSource;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("demo")
@TestPropertySource(properties = {
        "sentinelops.evidence.mode=real",
        "sentinelops.evidence.prometheus.base-url=http://127.0.0.1:9090",
        "sentinelops.evidence.loki.base-url=http://127.0.0.1:3100"
})
class RealEvidenceDiagnosisIT extends PostgresIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private IncidentApplicationService incidents;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private WebApplicationContext web;
    @Autowired private EvalApplicationService evaluations;
    @MockitoSpyBean private ModelGateway gateway;

    @Test
    void offlineEvalUsesOnlyFixtureEvidenceInRealMode() {
        var admin = new CurrentPrincipal("https://issuer.sentinelops.test", "real-mode-eval-admin",
                Set.of(PlatformRole.PLATFORM_ADMIN), Set.of());
        long snapshotsBefore = jdbc.sql("select count(*) from evidence_snapshot")
                .query(Long.class).single();
        var run = evaluations.run(new EvalApplicationService.RunRequest("incidents-v1", null),
                "real-mode-eval-" + UUID.randomUUID(), admin);
        assertThat(run.releaseAllowed()).isTrue();
        assertThat(run.results()).hasSize(12)
                .allSatisfy(result -> assertThat(result.status()).isEqualTo("passed"));
        assertThat(jdbc.sql("select count(*) from evidence_snapshot").query(Long.class).single())
                .isEqualTo(snapshotsBefore);
    }

    @Test
    void realModeRegistersBothSourcesAndNeverSeedsFixedEvidence() {
        assertThat(context.getBeansOfType(EvidenceSource.class).values())
                .extracting(EvidenceSource::sourceType)
                .containsExactlyInAnyOrder("prometheus", "loki");
        assertThat(context.getBeansOfType(EvidenceCaptureService.class)).hasSize(1);
        assertThat(context.getBeansOfType(AlertEvidenceCollector.class)).isEmpty();

        var alert = new AlertEnvelope("alertmanager", "real-mode-" + UUID.randomUUID(),
                "checkout-api", "real-mode-pool-" + UUID.randomUUID(),
                "Checkout connection acquisition timed out", "sev1",
                AlertEnvelope.AlertStatus.FIRING,
                json.createObjectNode().put("status", "firing"));
        var incident = incidents.ingest(alert);
        var sourceRefs = jdbc.sql("""
                        select source_ref from evidence_snapshot where incident_id=:incident
                        """).param("incident", incident.id()).query(String.class).list();
        assertThat(sourceRefs).doesNotContainAnyElementsOf(Set.of("E-12", "E-13"));
        assertThat(sourceRefs).isEmpty();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SENTINELOPS_REAL_EVIDENCE_IT", matches = "true")
    void faultTrafficProducesCitedPrometheusAndLokiEvidence() throws Exception {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var demoSecret = System.getenv().getOrDefault(
                "SENTINELOPS_DEMO_CONTROLLER_CLIENT_SECRET", "sentinelops-demo-controller-secret");
        var tokenRequest = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:8081/realms/sentinelops/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials&client_id=sentinelops-demo-controller"
                                + "&client_secret=" + URLEncoder.encode(demoSecret, StandardCharsets.UTF_8)
                                + "&scope=demo%3Afault"))
                .build();
        var tokenResponse = http.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResponse.statusCode()).isEqualTo(200);
        String token = json.readTree(tokenResponse.body()).path("access_token").asString();
        assertThat(token).isNotBlank();

        try {
        var fault = http.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:8082/internal/demo/faults/connection-pool"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(fault.statusCode()).isEqualTo(200);
        for (int index = 0; index < 12; index++) sendCheckout(http);
        Thread.sleep(2500);
        for (int index = 0; index < 12; index++) sendCheckout(http);

        awaitSignal(http, "http://127.0.0.1:9090/api/v1/query?query=demo_pool_pending", "12");
        String lokiQuery = URLEncoder.encode(
                "{service_name=\"sentinelops-demo-service\"} |= \"Demo checkout acquire timeout\"",
                StandardCharsets.UTF_8);
        awaitSignal(http, "http://127.0.0.1:3100/loki/api/v1/query_range?query=" + lokiQuery,
                "Demo checkout acquire timeout");

        var sourceByType = context.getBeansOfType(EvidenceSource.class).values().stream()
                .collect(java.util.stream.Collectors.toMap(EvidenceSource::sourceType, source -> source));
        var probeEnd = Instant.now();
        var probeBudget = new EvidenceBudget(200, 128 * 1024, Duration.ofMinutes(15));
        for (var query : List.of(
                Map.entry("prometheus", "checkout_error_rate"),
                Map.entry("prometheus", "checkout_latency_mean"),
                Map.entry("prometheus", "pool_pending"),
                Map.entry("loki", "acquire_timeout_logs"))) {
            try {
                var captured = sourceByType.get(query.getKey()).capture(new EvidenceQuery(
                        UUID.randomUUID(), UUID.fromString("0199a000-0000-7000-8000-000000000001"),
                        query.getValue(), Map.of(), probeEnd.minusSeconds(900), probeEnd), probeBudget);
                assertThat(captured.sourceType()).isEqualTo(query.getKey());
            } catch (RuntimeException failure) {
                throw new AssertionError("Registered evidence query failed: " + query.getValue(), failure);
            }
        }

        var alert = new AlertEnvelope("alertmanager", "real-flow-" + UUID.randomUUID(),
                "checkout-api", "real-flow-pool-" + UUID.randomUUID(),
                "Checkout connection acquisition timed out", "sev1",
                AlertEnvelope.AlertStatus.FIRING,
                json.createObjectNode().put("status", "firing"));
        var incident = incidents.ingest(alert);
        var gatewayFailure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (RuntimeException failure) {
                gatewayFailure.set(failure);
                throw failure;
            }
        }).when(gateway).diagnose(any());
        var mockMvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        var result = mockMvc.perform(post("/api/v1/incidents/{id}/diagnosis-runs", incident.id())
                        .with(jwt().authorities(new SimpleGrantedAuthority(API_AUTHORITY),
                                        new SimpleGrantedAuthority("ROLE_ON_CALL_OPERATOR"))
                                .jwt(jwt -> jwt.issuer("https://issuer.sentinelops.test")
                                        .subject("real-evidence-operator")
                                        .audience(List.of("sentinelops-api"))
                                        .claim("realm_access", Map.of("roles", List.of("on_call_operator")))
                                        .claim("service_ids", List.of(incident.serviceId().toString()))))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "real-diagnosis-" + UUID.randomUUID()))
                .andReturn();
        if (gatewayFailure.get() != null) {
            throw new AssertionError("Real model gateway failed", gatewayFailure.get());
        }
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        var proposal = json.readValue(result.getResponse().getContentAsString(), DiagnosisProposal.class);
        assertThat(proposal.runbookVersionId()).isNotNull();
        assertThat(jdbc.sql("select lifecycle from runbook_version where id=:id")
                .param("id", proposal.runbookVersionId()).query(String.class).single())
                .isEqualTo("published");
        assertThat(proposal.hypotheses()).hasSize(1);

        var snapshots = jdbc.sql("""
                        select id, source_type, source_ref, content_hash from evidence_snapshot
                        where diagnosis_run_id=:run order by source_type, source_ref
                        """).param("run", proposal.diagnosisRunId())
                .query((row, number) -> Map.of(
                        "id", row.getString("id"),
                        "source", row.getString("source_type"),
                        "ref", row.getString("source_ref"),
                        "hash", row.getString("content_hash"))).list();
        assertThat(snapshots).hasSize(4);
        assertThat(snapshots).extracting(item -> item.get("source"))
                .contains("prometheus", "loki");
        assertThat(snapshots).extracting(item -> item.get("ref"))
                .contains("pool_pending", "acquire_timeout_logs")
                .doesNotContain("E-12", "E-13");
        assertThat(snapshots).allSatisfy(item -> assertThat(item.get("hash"))
                .matches("[a-f0-9]{64}"));
        assertThat(proposal.hypotheses().getFirst().evidenceRefs()).hasSize(2);
        var citedIds = proposal.hypotheses().getFirst().evidenceRefs().stream()
                .map(UUID::toString).toList();
        assertThat(snapshots.stream()
                .filter(item -> Set.of("pool_pending", "acquire_timeout_logs").contains(item.get("ref")))
                .map(item -> item.get("id")).toList())
                .containsExactlyInAnyOrderElementsOf(citedIds);
        var calls = jdbc.sql("select tool_call_count from diagnosis_run where id=:run")
                .param("run", proposal.diagnosisRunId()).query(Integer.class).single();
        assertThat(calls).isEqualTo(4);
        } finally {
            var cleared = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:8082/internal/demo/faults"))
                    .header("Authorization", "Bearer " + token)
                    .DELETE().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(cleared.statusCode()).isEqualTo(200);
        }
    }

    private void sendCheckout(HttpClient http) throws Exception {
        var response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:8082/api/checkout"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(503);
    }

    private void awaitSignal(HttpClient http, String endpoint, String marker) throws Exception {
        Instant deadline = Instant.now().plusSeconds(40);
        while (Instant.now().isBefore(deadline)) {
            var response = http.send(HttpRequest.newBuilder(URI.create(endpoint)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body().contains(marker)) return;
            Thread.sleep(1000);
        }
        throw new AssertionError("Real evidence source did not expose the expected Demo signal");
    }
}
