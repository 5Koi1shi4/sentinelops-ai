package io.sentinelops.api.reliability;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.sentinelops.api.execution.adapter.out.persistence.OutboxStore;
import io.sentinelops.api.diagnosis.application.DiagnosisApplicationService;
import io.sentinelops.api.diagnosis.application.model.ModelDiagnosisRequest;
import io.sentinelops.api.diagnosis.application.model.ModelGateway;
import io.sentinelops.api.diagnosis.application.model.ToolBudget;
import io.sentinelops.api.execution.adapter.out.stream.ExecutionStreamPublisher;
import io.sentinelops.api.execution.adapter.out.stream.OutboxRelay;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.identity.application.PrincipalLookup;
import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Dependency outage checks against dedicated PostgreSQL/Valkey containers and real HTTP clients. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(DependencyFailureIT.FailureTestConfiguration.class)
class DependencyFailureIT {

    private static final String WEBHOOK_SECRET = "dependency-failure-webhook-secret-long-enough";
    private static final String ISSUER = "https://issuer.sentinelops.test";
    private static final UUID CHECKOUT_SERVICE =
            UUID.fromString("0199a000-0000-7000-8000-000000000001");
    private static final String MODEL_PATH = "/v1/chat/completions";
    private static final String LOKI_PATH = "/loki/api/v1/query_range";

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("sentinelops")
            .withUsername("sentinelops")
            .withPassword("sentinelops");
    @Container
    private static final GenericContainer<?> VALKEY = new GenericContainer<>(
                    DockerImageName.parse("valkey/valkey:8.1.10-alpine"))
            .withExposedPorts(6379);

    private static final WireMockServer MODEL = new WireMockServer(0);
    private static final WireMockServer LOKI = new WireMockServer(0);
    private static final WireMockServer TELEMETRY = new WireMockServer(0);

    @Autowired private IncidentApplicationService incidents;
    @Autowired private DiagnosisApplicationService diagnoses;
    @Autowired private PrincipalLookup principals;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Value("${local.server.port}") private int serverPort;
    private final HttpClient http = HttpClient.newHttpClient();
    @Autowired private OutboxStore outbox;
    @Autowired private ExecutionStreamPublisher streamPublisher;
    @Autowired private MeterRegistry meters;

    @DynamicPropertySource
    static void dependencyProperties(DynamicPropertyRegistry registry) {
        startHttpDependencies();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost.invalid/sentinelops-test-jwks");
        registry.add("sentinelops.security.issuer", () -> ISSUER);
        registry.add("sentinelops.outbox.relay-enabled", () -> false);
        registry.add("sentinelops.outbox.stream", () -> "sentinelops.dependency-failure-it");
        registry.add("sentinelops.verification.scheduler-enabled", () -> false);
        registry.add("sentinelops.verification.max-delay", () -> "PT0S");
        registry.add("sentinelops.verification.demo-checkout-base-url", () -> "http://localhost.invalid");
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("sentinelops.webhook.source-refs",
                () -> "test-alertmanager=env:SENTINELOPS_DEPENDENCY_WEBHOOK_SECRET");
        registry.add("SENTINELOPS_DEPENDENCY_WEBHOOK_SECRET", () -> WEBHOOK_SECRET);
        registry.add("sentinelops.ai.provider", () -> "openai-compatible");
        registry.add("sentinelops.ai.base-url", () -> MODEL.baseUrl() + "/v1");
        registry.add("sentinelops.ai.model", () -> "dependency-failure-model");
        registry.add("sentinelops.ai.embedding-model", () -> "dependency-failure-embedding");
        registry.add("sentinelops.ai.embedding-dimensions", () -> "1536");
        registry.add("sentinelops.ai.api-key-secret-ref", () -> "env:SENTINELOPS_DEPENDENCY_MODEL_KEY");
        registry.add("SENTINELOPS_DEPENDENCY_MODEL_KEY", () -> "test-only-model-key");
        registry.add("sentinelops.evidence.mode", () -> "real");
        registry.add("sentinelops.evidence.prometheus.base-url", LOKI::baseUrl);
        registry.add("sentinelops.evidence.loki.base-url", LOKI::baseUrl);
        registry.add("management.tracing.sampling.probability", () -> "1.0");
        registry.add("management.opentelemetry.tracing.export.otlp.enabled", () -> "false");
        registry.add("management.opentelemetry.logging.export.otlp.enabled", () -> "false");
        registry.add("management.otlp.metrics.export.enabled", () -> "false");
    }

    @BeforeEach
    void clearModelAndLokiFixtures() {
        MODEL.resetAll();
        LOKI.resetAll();
    }

    @AfterAll
    static void stopHttpDependencies() {
        if (MODEL.isRunning()) MODEL.stop();
        if (LOKI.isRunning()) LOKI.stop();
        if (TELEMETRY.isRunning()) TELEMETRY.stop();
    }

    @Test
    @Order(1)
    void modelTimeoutLeavesIncidentInManualTriageWithNoProposalOrExecution() {
        MODEL.stubFor(post(urlPathEqualTo(MODEL_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(6000)
                .withBody(validChatCompletion("{}"))));
        UUID incidentId = createIncident("model-timeout-" + UUID.randomUUID());
        var principal = operator();
        UUID principalId = principals.upsert(principal, "dependency failure test operator");

        long startedNanos = System.nanoTime();
        Throwable failure = catchThrowable(() -> diagnoses.diagnose(
                incidentId, 0, "model-timeout-" + UUID.randomUUID(), principal, principalId));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

        assertThat(failure).isInstanceOfSatisfying(ApiProblemException.class, problem -> {
            assertThat(problem.errorCode()).isEqualTo("MODEL_TIMEOUT");
            assertThat(problem.status().value()).isEqualTo(504);
        });
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        MODEL.verify(moreThan(0), postRequestedFor(urlPathEqualTo(MODEL_PATH)));
        assertThat(jdbc.sql("select status from incident where id=:id")
                        .param("id", incidentId).query(String.class).single())
                .isEqualTo("triaging");
        assertThat(jdbc.sql("select status from diagnosis_run where incident_id=:id")
                        .param("id", incidentId).query(String.class).single())
                .isEqualTo("failed");
        assertThat(jdbc.sql("select failure_code from diagnosis_run where incident_id=:id")
                        .param("id", incidentId).query(String.class).single())
                .isEqualTo("MODEL_TIMEOUT");
        assertThat(jdbc.sql("select count(*) from diagnosis_proposal where incident_id=:id")
                        .param("id", incidentId).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from execution where incident_id=:id")
                        .param("id", incidentId).query(Long.class).single())
                .isZero();
    }

    @Test
    @Order(2)
    void lokiUnavailableFailsDiagnosisClosedWithoutMissingEvidenceOrUnsafeProposal() {
        MODEL.stubFor(post(urlPathEqualTo(MODEL_PATH)).willReturn(okJson(lokiQueryToolCall())));
        LOKI.stubFor(get(urlPathEqualTo(LOKI_PATH)).willReturn(aResponse().withStatus(503)));
        UUID incidentId = createIncident("loki-down-" + UUID.randomUUID());
        var principal = operator();
        UUID principalId = principals.upsert(principal, "dependency failure test operator");

        Throwable failure = catchThrowable(() -> diagnoses.diagnose(
                incidentId, 0, "loki-down-" + UUID.randomUUID(), principal, principalId));

        assertThat(failure).isInstanceOf(ApiProblemException.class);
        MODEL.verify(moreThan(0), postRequestedFor(urlPathEqualTo(MODEL_PATH)));
        LOKI.verify(moreThan(0), getRequestedFor(urlPathEqualTo(LOKI_PATH)));
        assertThat(jdbc.sql("select count(*) from evidence_snapshot where incident_id=:id and source_type='loki'")
                        .param("id", incidentId).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("select status from incident where id=:id")
                        .param("id", incidentId).query(String.class).single())
                .isEqualTo("triaging");
        assertThat(jdbc.sql("select status from diagnosis_run where incident_id=:id")
                        .param("id", incidentId).query(String.class).single())
                .isEqualTo("failed");
        assertThat(jdbc.sql("select count(*) from diagnosis_proposal where incident_id=:id")
                        .param("id", incidentId).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from execution where incident_id=:id")
                        .param("id", incidentId).query(Long.class).single())
                .isZero();
    }

    @Test
    @Order(3)
    void unavailableOtlpCollectorDoesNotRejectOrLoseAReceivedAlert() throws Exception {
        TELEMETRY.resetAll();
        TELEMETRY.stubFor(post(urlPathEqualTo("/v1/traces"))
                .willReturn(aResponse().withStatus(503)));
        int priorTraceExports = TELEMETRY.getAllServeEvents().size();
        String fingerprint = "telemetry-down-" + UUID.randomUUID();

        HttpResponse<String> response = postWebhook(fingerprint);

        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode body = mapper.readTree(response.body());
        UUID incidentId = UUID.fromString(body.path("id").asString());
        assertThat(jdbc.sql("select count(*) from incident where id=:id")
                        .param("id", incidentId).query(Long.class).single())
                .isOne();
        assertTelemetryRequestAfter(priorTraceExports);
        TELEMETRY.verify(moreThan(0), postRequestedFor(urlPathEqualTo("/v1/traces")));
    }

    @Test
    @Order(4)
    void valkeyUnavailableLeavesExecutionOutboxBackloggedWithoutCreatingAttempt() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        outbox.insert(eventId, executionId, "execution.requested.v1",
                "{\"eventId\":\"" + eventId + "\",\"executionId\":\"" + executionId + "\"}",
                Instant.now());
        var relay = new OutboxRelay(outbox, streamPublisher, meters, "dependency-failure-it",
                1, 256, Duration.ofMillis(50), Duration.ofMinutes(1), 20);

        var paused = VALKEY.execInContainer("valkey-cli", "CLIENT", "PAUSE", "10000", "ALL");
        assertThat(paused.getExitCode()).as(paused.getStdout() + paused.getStderr()).isZero();
        try {
            assertThat(relay.relayOnce()).isZero();
            assertThat(outbox.unpublishedForAggregate(executionId))
                    .extracting(OutboxStore.OutboxRecord::id)
                    .containsExactly(eventId);
            assertThat(jdbc.sql("select publish_attempts from outbox_event where id=:id")
                            .param("id", eventId).query(Integer.class).single())
                    .isOne();
            assertThat(jdbc.sql("select count(*) from execution_attempt where execution_id=:id")
                            .param("id", executionId).query(Long.class).single())
                    .isZero();
        } finally {
            var resumed = VALKEY.execInContainer("valkey-cli", "CLIENT", "UNPAUSE");
            assertThat(resumed.getExitCode()).as(resumed.getStdout() + resumed.getStderr()).isZero();
        }
    }

    @Test
    @Order(5)
    void postgresDatabaseUnavailableReturns503AndDoesNotAcknowledgeTheAlert() throws Exception {
        String fingerprint = "postgres-down-" + UUID.randomUUID();
        setBusinessDatabaseAvailable(false);
        HttpResponse<String> response;
        try {
            response = postWebhook(fingerprint);
        } finally {
            setBusinessDatabaseAvailable(true);
        }

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("dependency_unavailable");
        assertThat(countPersistedIncidents(fingerprint)).isZero();
    }

    private UUID createIncident(String fingerprint) {
        var payload = mapper.createObjectNode().put("status", "firing");
        var alert = new AlertEnvelope("alertmanager", "dependency-" + fingerprint,
                "checkout-api", fingerprint, "Dependency failure integration test", "sev2",
                AlertEnvelope.AlertStatus.FIRING, payload);
        return incidents.ingest(alert).id();
    }

    private CurrentPrincipal operator() {
        return new CurrentPrincipal(ISSUER, "dependency-test-" + UUID.randomUUID(),
                Set.of(PlatformRole.ON_CALL_OPERATOR), Set.of(CHECKOUT_SERVICE));
    }

    private HttpResponse<String> postWebhook(String fingerprint) throws Exception {
        var payload = mapper.createObjectNode()
                .put("version", "4")
                .put("groupKey", fingerprint)
                .put("status", "firing");
        payload.putObject("commonLabels")
                .put("service_key", "checkout-api")
                .put("severity", "sev2")
                .put("incident_fingerprint", fingerprint)
                .put("alertname", "DependencyFailure");
        payload.putObject("commonAnnotations").put("summary", "Synthetic dependency failure alert");
        payload.putArray("alerts").addObject()
                .put("status", "firing")
                .put("fingerprint", fingerprint)
                .putObject("labels")
                .put("service_key", "checkout-api")
                .put("severity", "sev2")
                .put("alertname", "DependencyFailure");

        byte[] rawBody = mapper.writeValueAsBytes(payload);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8));
        String signature = "v1=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort
                        + "/api/v1/integrations/alertmanager/webhook"))
                .header("Content-Type", "application/json")
                .header("X-Sentinel-Source", "test-alertmanager")
                .header("X-Sentinel-Timestamp", timestamp)
                .header("X-Sentinel-Nonce", nonce)
                .header("X-Sentinel-Signature", signature)
                .header("X-SentinelOps-Event-Id", "dependency-event-" + nonce)
                .POST(HttpRequest.BodyPublishers.ofByteArray(rawBody))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private String validChatCompletion(String content) {
        return """
                {"id":"dependency-test","object":"chat.completion","created":1,
                 "model":"dependency-failure-model","choices":[{"index":0,
                 "message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}]}
                """.formatted(content);
    }

    private String lokiQueryToolCall() {
        return """
                {"id":"dependency-loki-tool","object":"chat.completion","created":1,
                 "model":"dependency-failure-model","choices":[{"index":0,"message":{
                 "role":"assistant","content":null,"tool_calls":[{"id":"query-loki-1",
                 "type":"function","function":{"name":"queryLogs","arguments":
                 "{\\"queryId\\":\\"acquire_timeout_logs\\",\\"parameters\\":{}}"}}]},
                 "finish_reason":"tool_calls"}]}
                """;
    }

    private void setBusinessDatabaseAvailable(boolean available) throws SQLException {
        String applicationUrl = POSTGRES.getJdbcUrl();
        String adminUrl = applicationUrl.substring(0, applicationUrl.lastIndexOf('/') + 1) + "postgres";
        try (var admin = DriverManager.getConnection(adminUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            if (available) {
                statement.execute("alter database sentinelops with allow_connections true");
            } else {
                statement.execute("alter database sentinelops with allow_connections false");
                statement.execute("select pg_terminate_backend(pid) from pg_stat_activity "
                        + "where datname='sentinelops' and pid <> pg_backend_pid()");
            }
        }
    }

    private long countPersistedIncidents(String fingerprint) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var query = connection.prepareStatement(
                        "select count(*) from incident where fingerprint = ?")) {
            query.setString(1, fingerprint);
            try (var result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void assertTelemetryRequestAfter(int priorEvents) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (TELEMETRY.getAllServeEvents().size() <= priorEvents && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(TELEMETRY.getAllServeEvents().size()).isGreaterThan(priorEvents);
    }

    private static void startHttpDependencies() {
        if (!MODEL.isRunning()) MODEL.start();
        if (!LOKI.isRunning()) LOKI.start();
        if (!TELEMETRY.isRunning()) TELEMETRY.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureTestConfiguration {
        @Bean
        @Primary
        ModelGateway boundedFailureTestModelGateway(@Qualifier("modelGateway") ModelGateway configuredGateway) {
            return request -> configuredGateway.diagnose(new ModelDiagnosisRequest(
                    request.context(), request.toolContext(), request.promptVersion(),
                    new ToolBudget(request.budget().maxTotalCalls(), Duration.ofMillis(3000))));
        }

        @Bean
        SdkTracerProviderBuilderCustomizer unavailableOtlpCollector() {
            var exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(TELEMETRY.baseUrl() + "/v1/traces")
                    .build();
            return builder -> builder.addSpanProcessor(SimpleSpanProcessor.create(exporter));
        }
    }
}
