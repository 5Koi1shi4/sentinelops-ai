package io.sentinelops.executor.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.sentinelops.executor.adapter.AdapterContractTest;
import io.sentinelops.executor.controlplane.ExecutorOAuth2TokenProvider;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.RunbookAdapter;
import io.sentinelops.executor.runbook.UnsupportedRunbookStepException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductionHttpRunbookAdapterTest implements AdapterContractTest {
    private HttpServer server;
    private ExecutorOAuth2TokenProvider tokens;
    private ProductionHttpRunbookAdapter adapter;
    private AtomicInteger calls;
    private AtomicInteger dispatched;

    @BeforeEach
    void setup() throws Exception {
        calls = new AtomicInteger();
        dispatched = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/approved/restart", exchange -> {
            calls.incrementAndGet();
            assertThat(dispatched).hasValue(1);
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer target-token");
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                    .isEqualTo(executionId + ":step-one");
            assertThat(exchange.getRequestHeaders().getFirst("X-SentinelOps-Fencing-Token")).isEqualTo("19");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"replicas\":2");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/approved/restart");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        tokens = mock(ExecutorOAuth2TokenProvider.class);
        when(tokens.accessToken("target-service", "runbook:execute"))
                .thenReturn("target-token");
        adapter = adapterFor("/approved/restart");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Override public RunbookAdapter adapter() { return adapter; }
    @Override public String allowedOperation() { return "restart_service"; }
    @Override public String allowedTarget() { return "checkout"; }
    @Override public Map<String, Object> allowedParameters() { return Map.of("replicas", 2); }

    @Test
    void sendsOnlyCatalogedRequestAfterDispatchMarker() {
        var result = adapter.execute(step("restart_service", "checkout", Map.of("replicas", 2)),
                context(), dispatched::incrementAndGet);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.requestHash()).isNotBlank();
        assertThat(dispatched).hasValue(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void refusesRedirectAndDoesNotReachRedirectDestination() {
        adapter = adapterFor("/redirect");
        assertThatThrownBy(() -> adapter.execute(
                step("restart_service", "checkout", Map.of("replicas", 2)), context()))
                .isInstanceOf(ProductionHttpRunbookAdapter.HttpTargetException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsIpLiteralWithoutExplicitPrivateRegistrationAndInvalidParameter() {
        assertThatThrownBy(() -> new HttpActionCatalog(Map.of("restart_service", new HttpActionCatalog.Action(
                "checkout", URI.create("http://127.0.0.1:8080/approved/restart"), "POST",
                "target-service", "runbook:execute",
                Map.of("replicas", HttpActionCatalog.ParameterRule.integer(1, 3)),
                Map.of("replicas", "${replicas}"), false))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.execute(
                step("restart_service", "checkout", Map.of("replicas", 9)), context(),
                () -> { throw new AssertionError("Invalid parameter reached transport"); }))
                .isInstanceOf(UnsupportedRunbookStepException.class);
    }

    @Test
    void rejectsPrivateIpv6AndCarrierGradeNatDnsAnswers() throws Exception {
        assertThat(ProductionHttpRunbookAdapter.isPrivateAddress(
                InetAddress.getByName("fc00::1"))).isTrue();
        assertThat(ProductionHttpRunbookAdapter.isPrivateAddress(
                InetAddress.getByName("100.64.0.1"))).isTrue();
        assertThat(ProductionHttpRunbookAdapter.isPrivateAddress(
                InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    void pinsTheCheckedAddressForTheActualHttpConnection() throws Exception {
        var uri = URI.create("http://action.example.test:" + server.getAddress().getPort()
                + "/approved/restart");
        var catalog = new HttpActionCatalog(Map.of("restart_service", new HttpActionCatalog.Action(
                "checkout", uri, "POST", "target-service", "runbook:execute",
                Map.of("replicas", HttpActionCatalog.ParameterRule.integer(1, 3)),
                Map.of("replicas", "${replicas}"), true)));
        var resolutions = new AtomicInteger();
        adapter = new ProductionHttpRunbookAdapter(catalog, tokens, new ObjectMapper(), host -> {
            assertThat(host).isEqualTo("action.example.test");
            resolutions.incrementAndGet();
            return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        });
        var result = adapter.execute(step("restart_service", "checkout", Map.of("replicas", 2)),
                context(), dispatched::incrementAndGet);
        assertThat(result.succeeded()).isTrue();
        assertThat(resolutions).hasValue(1);
        assertThat(calls).hasValue(1);
    }

    private ProductionHttpRunbookAdapter adapterFor(String path) {
        var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        var catalog = new HttpActionCatalog(Map.of("restart_service", new HttpActionCatalog.Action(
                "checkout", uri, "POST", "target-service", "runbook:execute",
                Map.of("replicas", HttpActionCatalog.ParameterRule.integer(1, 3)),
                Map.of("replicas", "${replicas}"), true)));
        return new ProductionHttpRunbookAdapter(catalog, tokens, new ObjectMapper());
    }

    private IdempotencyContext context() { return new IdempotencyContext(executionId, "step-one", 19); }
    private UUID executionId = UUID.randomUUID();
    private AuthorizedRunbookStep step(String operation, String target, Map<String, Object> params) {
        return new AuthorizedRunbookStep(executionId, UUID.randomUUID(), UUID.randomUUID(),
                "checksum", "step-one", operation, "production-http", params, target, "R1", 19);
    }
}
