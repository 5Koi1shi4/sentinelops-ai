package io.sentinelops.api.incident.evidence;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.observation.ObservationRegistry;
import io.sentinelops.api.incident.application.evidence.*;
import io.sentinelops.api.incident.adapter.out.http.BoundedRestClientFactory;
import io.sentinelops.api.incident.adapter.out.loki.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LokiEvidenceSourceTest implements EvidenceSourceContractTest {
    private WireMockServer server;
    private EvidenceSource adapter;
    @BeforeEach void setUp() {
        server = new WireMockServer(0);
        server.start();
        var template = new EvidenceQueryTemplate("{instance=\"${instance}\"} |= \"timeout\"",
                Map.of("instance", new EvidenceParameter("checkout-[0-9]+", Set.of("checkout-1"))),
                Set.of("instance"), Duration.ofSeconds(5));
        adapter = new LokiEvidenceSource(new LokiProperties(URI.create(server.baseUrl()),
                Map.of(SERVICE, Map.of("checkout", template)), 65_536),
                new BoundedRestClientFactory(), ObservationRegistry.NOOP);
    }
    @AfterEach void stop() { server.stop(); }
    public EvidenceSource source() { return adapter; }
    public WireMockServer provider() { return server; }
    public String endpoint() { return "/loki/api/v1/query_range"; }
    public String successfulBody() {
        return """
                {"status":"success","data":{"resultType":"streams","result":[
                  {"stream":{"instance":"checkout-1","secret":"not-allowed"},
                   "values":[["1790035201000000000","timeout b"],["1790035200000000000","timeout a"]]}]}}
                """;
    }
    @Test void usesBackwardDirectionLineLimitAndAllowedLabels() {
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody())));
        var result = source().capture(query(), budget());
        server.verify(getRequestedFor(urlPathEqualTo(endpoint()))
                .withQueryParam("query", equalTo("{instance=\"checkout-1\"} |= \"timeout\""))
                .withQueryParam("direction", equalTo("backward"))
                .withQueryParam("limit", equalTo("20"))
                .withQueryParam("start", equalTo("1790035200000000000")));
        assertThat(result.sourceType()).isEqualTo("loki");
        assertThat(result.items().getFirst().value()).isEqualTo("timeout a");
        assertThat(result.items().getFirst().labels()).containsOnlyKeys("instance");
    }
    @Test void truncatesDeterministicallyByItemsAndBytes() {
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody())));
        var result = source().capture(query(), new EvidenceBudget(1, 2048, Duration.ofMinutes(15)));
        assertThat(result.items()).hasSize(1);
        assertThat(result.truncated()).isTrue();
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody()
                .replace("timeout a", "超时".repeat(2000)))));
        var capped = source().capture(query(), budget());
        assertThat(capped.serializedBytes()).isLessThanOrEqualTo(2048);
        assertThat(capped.truncated()).isTrue();
    }
}
