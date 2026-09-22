package io.sentinelops.api.incident.evidence;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.sentinelops.api.incident.adapter.out.http.BoundedRestClientFactory;
import io.sentinelops.api.incident.application.evidence.EvidenceBudgetExceeded;
import io.sentinelops.api.incident.application.evidence.EvidenceSourceException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoundedRestClientFactoryTest {

    private WireMockServer server;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void parsesJsonWithoutProviderMetadataInResult() {
        server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(okJson("{\"ok\":true}")));

        var result = new BoundedRestClientFactory().get(uri("/evidence"), 2_048);

        assertThat(result.path("ok").asBoolean()).isTrue();
    }

    @Test
    void rejectsSubMillisecondTimeoutConfiguration() {
        assertThatThrownBy(() -> new BoundedRestClientFactory(Duration.ofNanos(999_999), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedRestClientFactory(Duration.ofMillis(1), Duration.ofNanos(999_999)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBodyThatExceedsStreamingCap() {
        server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"payload\":\"" + "x".repeat(256) + "\"}")));

        assertThatThrownBy(() -> new BoundedRestClientFactory().get(uri("/evidence"), 128))
                .isInstanceOf(EvidenceBudgetExceeded.class)
                .hasMessage("provider response exceeds byte budget")
                .hasNoCause();
    }

    @Test
    void rejectsRedirectWithoutFollowingIt() {
        var attacker = new WireMockServer(0);
        try {
            attacker.start();
            server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse()
                    .withStatus(302)
                    .withHeader("Location", attacker.baseUrl() + "/stolen")));

            assertThatThrownBy(() -> new BoundedRestClientFactory().get(uri("/evidence"), 2_048))
                    .isInstanceOf(EvidenceSourceException.class)
                    .hasMessage("evidence provider redirect rejected")
                    .hasNoCause();
            assertThat(attacker.getAllServeEvents()).isEmpty();
        } finally {
            attacker.stop();
        }
    }

    @Test
    void rejectsCompressedResponsesBeforeReadingThem() {
        server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse()
                .withHeader("Content-Encoding", "gzip")
                .withBody("compressed")));

        assertThatThrownBy(() -> new BoundedRestClientFactory().get(uri("/evidence"), 2_048))
                .isInstanceOf(EvidenceSourceException.class)
                .hasMessage("unsupported provider content encoding")
                .hasNoCause();
    }

    @Test
    void retriesOnlyBadGatewayAndUnavailableOncePerAttempt() {
        for (var status : new int[] {502, 503}) {
            server.resetAll();
            server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse().withStatus(status)));

            assertThatThrownBy(() -> new BoundedRestClientFactory().get(uri("/evidence"), 2_048))
                    .isInstanceOf(EvidenceSourceException.class)
                    .hasMessage("evidence provider unavailable")
                    .hasNoCause();
            server.verify(2, getRequestedFor(urlPathEqualTo("/evidence")));
        }
    }

    @Test
    void doesNotRetryRateLimitResponses() {
        server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse().withStatus(429)
                .withBody("provider secret")));

        assertThatThrownBy(() -> new BoundedRestClientFactory().get(uri("/evidence"), 2_048))
                .isInstanceOf(EvidenceSourceException.class)
                .hasMessage("evidence source rate limited")
                .hasNoCause();
        server.verify(1, getRequestedFor(urlPathEqualTo("/evidence")));
    }

    @Test
    void hidesMalformedJsonAndProviderBodyFromTypedFailure() {
        server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse()
                .withBody("secret-provider-body{")));

        assertThatThrownBy(() -> new BoundedRestClientFactory().get(uri("/evidence"), 2_048))
                .isInstanceOf(EvidenceSourceException.class)
                .hasMessage("evidence provider returned invalid JSON")
                .hasNoCause()
                .hasMessageNotContaining("secret-provider-body")
                .hasMessageNotContaining(server.baseUrl());
    }

    @Test
    void retriesConnectionResetExactlyOnce() {
        server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> new BoundedRestClientFactory().get(uri("/evidence"), 2_048))
                .isInstanceOf(EvidenceSourceException.class)
                .hasMessage("evidence provider unavailable")
                .hasNoCause();
        server.verify(2, getRequestedFor(urlPathEqualTo("/evidence")));
    }

    @Test
    void rejectsMalformedUtf8InsteadOfReplacingProviderBytes() {
        byte[] body = {123, 34, 120, 34, 58, 34, (byte) 0xc3, 40, 34, 125};
        server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse().withBody(body)));
        assertThatThrownBy(() -> new BoundedRestClientFactory().get(uri("/evidence"), 2048))
                .isInstanceOf(EvidenceSourceException.class).hasNoCause();
        server.verify(1, getRequestedFor(urlPathEqualTo("/evidence")));
    }

    @Test
    void capsChunkedResponseWithoutContentLength() {
        server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse()
                .withBody("{\"payload\":\"" + "x".repeat(256) + "\"}")
                .withChunkedDribbleDelay(8, 20)));

        assertThatThrownBy(() -> new BoundedRestClientFactory().get(uri("/evidence"), 128))
                .isInstanceOf(EvidenceBudgetExceeded.class)
                .hasMessage("provider response exceeds byte budget")
                .hasNoCause();
        server.verify(1, getRequestedFor(urlPathEqualTo("/evidence")));
    }

    @Test
    void opensCircuitPerHostAfterRepeatedTransientFailures() {
        server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse().withStatus(503)));
        var factory = new BoundedRestClientFactory();

        for (int call = 0; call < 2; call++) {
            assertThatThrownBy(() -> factory.get(uri("/evidence"), 2_048))
                    .isInstanceOf(EvidenceSourceException.class);
        }
        var requestsBeforeOpen = server.getAllServeEvents().size();

        assertThatThrownBy(() -> factory.get(uri("/evidence"), 2_048))
                .isInstanceOf(EvidenceSourceException.class)
                .hasNoCause();

        assertThat(server.getAllServeEvents()).hasSize(requestsBeforeOpen);
    }

    @Test
    void countsProviderTimeoutsForCircuitWithoutRetryingThem() {
        server.stubFor(get(urlPathEqualTo("/timeout")).willReturn(aResponse()
                .withFixedDelay(250)
                .withBody("{\"ok\":true}")));
        var factory = new BoundedRestClientFactory(Duration.ofSeconds(1), Duration.ofMillis(100));

        for (int call = 0; call < 4; call++) {
            assertThatThrownBy(() -> factory.get(uri("/timeout"), 2_048))
                    .isInstanceOf(EvidenceSourceException.class)
                    .hasMessage("evidence provider timed out")
                    .hasNoCause();
        }
        var requestsBeforeOpen = server.getAllServeEvents().size();
        assertThat(requestsBeforeOpen).isEqualTo(4);

        assertThatThrownBy(() -> factory.get(uri("/timeout"), 2_048))
                .isInstanceOf(EvidenceSourceException.class)
                .hasNoCause();
        assertThat(server.getAllServeEvents()).hasSize(requestsBeforeOpen);
    }

    @Test
    void isolatesCircuitBreakersByConfiguredEndpointPath() {
        server.stubFor(get(urlPathEqualTo("/unhealthy")).willReturn(aResponse().withStatus(503)));
        server.stubFor(get(urlPathEqualTo("/healthy")).willReturn(okJson("{\"ok\":true}")));
        var factory = new BoundedRestClientFactory();

        for (int call = 0; call < 2; call++) {
            assertThatThrownBy(() -> factory.get(uri("/unhealthy"), 2_048))
                    .isInstanceOf(EvidenceSourceException.class);
        }

        var healthy = factory.get(uri("/healthy"), 2_048);

        assertThat(healthy.path("ok").asBoolean()).isTrue();
        server.verify(1, getRequestedFor(urlPathEqualTo("/healthy")));
    }

    @Test
    void attemptDeadlineIncludesSlowResponseBody() {
        server.stubFor(get(urlPathEqualTo("/evidence")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true}")
                .withChunkedDribbleDelay(2, 400)));

        assertThatThrownBy(() -> new BoundedRestClientFactory(Duration.ofSeconds(1), Duration.ofMillis(150))
                .get(uri("/evidence"), 2_048))
                .isInstanceOf(EvidenceSourceException.class)
                .hasMessage("evidence provider timed out")
                .hasNoCause();
    }

    private URI uri(String path) {
        return URI.create(server.baseUrl() + path);
    }
}
