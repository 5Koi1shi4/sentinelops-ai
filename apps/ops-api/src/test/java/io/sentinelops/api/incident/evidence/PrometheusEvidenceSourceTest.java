package io.sentinelops.api.incident.evidence;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.observation.ObservationRegistry;
import io.sentinelops.api.incident.application.evidence.*;
import io.sentinelops.api.incident.adapter.out.http.BoundedRestClientFactory;
import io.sentinelops.api.incident.adapter.out.prometheus.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrometheusEvidenceSourceTest implements EvidenceSourceContractTest {
    private WireMockServer server;
    private EvidenceSource adapter;
    @BeforeEach void setUp() {
        server = new WireMockServer(0);
        server.start();
        var template = new EvidenceQueryTemplate("pool_pending{instance=\"${instance}\"}",
                Map.of("instance", new EvidenceParameter("checkout-[0-9]+", Set.of("checkout-1", "checkout-2"))),
                Set.of("instance"), Duration.ofSeconds(5));
        adapter = new PrometheusEvidenceSource(new PrometheusProperties(URI.create(server.baseUrl()),
                Map.of(SERVICE, Map.of("checkout", template)), 65_536),
                new BoundedRestClientFactory(), ObservationRegistry.NOOP);
    }
    @AfterEach void stop() { server.stop(); }
    public EvidenceSource source() { return adapter; }
    public WireMockServer provider() { return server; }
    public String endpoint() { return "/api/v1/query_range"; }
    public String successfulBody() {
        return """
                {"status":"success","warnings":["partial result"],"data":{"resultType":"matrix","result":[
                  {"metric":{"instance":"checkout-1","secret":"not-allowed"},
                   "values":[[1790035201,"2"],[1790035200,"1"]]}]}}
                """;
    }
    @Test void expandsConfiguredQueryAndCapsSampleCountWithStep() {
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody())));
        var result = source().capture(query(), budget());
        server.verify(getRequestedFor(urlPathEqualTo(endpoint()))
                .withQueryParam("query", equalTo("pool_pending{instance=\"checkout-1\"}"))
                .withQueryParam("step", equalTo("16"))
                .withQueryParam("start", equalTo("1790035200"))
                .withQueryParam("end", equalTo("1790035500")));
        assertThat(result.sourceType()).isEqualTo("prometheus");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().timestamp()).isEqualTo("1790035200");
        assertThat(result.items().getFirst().labels()).containsOnlyKeys("instance");
        assertThat(result.warnings()).containsExactly("partial result");
        assertThat(result.truncated()).isFalse();
    }
    @Test void hashesIndependentlyOfProviderSeriesAndSampleOrder() {
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody())));
        var first = source().capture(query(), budget());
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody()
                .replace("[1790035201,\"2\"],[1790035200,\"1\"]", "[1790035200,\"1\"],[1790035201,\"2\"]"))));
        assertThat(source().capture(query(), budget()).contentHash()).isEqualTo(first.contentHash());
    }

    @Test void rejectsMalformedSamplesAndUnsupportedHistograms() {
        for (String values : new String[] {"[[1790035200,{}]]", "[[\"bad-time\",\"1\"]]", "[[1790035200,\"oops\"]]"}) {
            server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody()
                    .replace("[[1790035201,\"2\"],[1790035200,\"1\"]]", values))));
            assertThatThrownBy(() -> source().capture(query(), budget())).isInstanceOf(EvidenceSourceException.class);
        }
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody()
                .replace("\"values\"", "\"histograms\""))));
        assertThatThrownBy(() -> source().capture(query(), budget())).isInstanceOf(EvidenceSourceException.class);
    }

    @Test void sortsDecimalTimestampsNumericallyAndCapsAcrossSeries() {
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody()
                .replace("[1790035201,\"2\"],[1790035200,\"1\"]", "[1790035210.25,\"2\"],[1790035202.5,\"1\"]"))));
        var result = source().capture(query(), new EvidenceBudget(1, 2048, Duration.ofMinutes(15)));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().timestamp()).isEqualTo("1790035202.5");
        assertThat(result.truncated()).isTrue();
    }

    @Test void preservesQueryOperatorsAndAcceptsTrailingSlashInConfiguredBase() {
        var template = new EvidenceQueryTemplate("up + 1", Map.of(), Set.of("instance"), Duration.ofSeconds(5));
        var configured = new PrometheusEvidenceSource(new PrometheusProperties(URI.create(server.baseUrl() + "/"),
                Map.of(SERVICE, Map.of("checkout", template)), 65536), new BoundedRestClientFactory(), ObservationRegistry.NOOP);
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody())));
        configured.capture(new EvidenceQuery(java.util.UUID.randomUUID(), SERVICE, "checkout", Map.of(), FROM,
                FROM.plusSeconds(300)), budget());
        server.verify(getRequestedFor(urlPathEqualTo(endpoint())).withQueryParam("query", equalTo("up + 1")));
    }

    @Test void rejectsAggregateSamplesAboveParsingHardLimitBeforeOutputTruncation() {
        var template = new EvidenceQueryTemplate("up", Map.of(), Set.of("instance"), Duration.ofSeconds(5));
        var configured = new PrometheusEvidenceSource(new PrometheusProperties(URI.create(server.baseUrl()),
                Map.of(SERVICE, Map.of("checkout", template)), EvidenceBudget.MAX_BYTES),
                new BoundedRestClientFactory(), ObservationRegistry.NOOP);
        String values = String.join(",", java.util.Collections.nCopies(5001, "[1790035200,\"1\"]"));
        String series = "{\"metric\":{\"instance\":\"checkout-1\"},\"values\":[" + values + "]}";
        String body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[" + series + "," + series + "]}}";
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(body)));
        var q = new EvidenceQuery(java.util.UUID.randomUUID(), SERVICE, "checkout", Map.of(), FROM, FROM.plusSeconds(300));
        assertThatThrownBy(() -> configured.capture(q, new EvidenceBudget(1, 2048, Duration.ofMinutes(15))))
                .isInstanceOf(EvidenceBudgetExceeded.class).hasMessage("provider response exceeds sample limit");
        server.verify(1, getRequestedFor(urlPathEqualTo(endpoint())));
    }

    @Test void appliesOneOutputBudgetAcrossAllSeriesInCanonicalOrder() {
        server.stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson("""
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"instance":"checkout-2"},"values":[[1790035200,"2"]]},
                  {"metric":{"instance":"checkout-1"},"values":[[1790035200,"1"]]}]}}
                """)));
        var result = source().capture(query(), new EvidenceBudget(1, 2048, Duration.ofMinutes(15)));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().labels()).containsEntry("instance", "checkout-1");
        assertThat(result.truncated()).isTrue();
    }
}
