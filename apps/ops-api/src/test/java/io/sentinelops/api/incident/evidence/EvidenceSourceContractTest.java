package io.sentinelops.api.incident.evidence;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.sentinelops.api.incident.application.evidence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

interface EvidenceSourceContractTest {
    UUID SERVICE = UUID.fromString("0199a90a-9c00-7000-8000-000000000010");
    Instant FROM = Instant.parse("2026-09-22T00:00:00Z");
    EvidenceSource source();
    WireMockServer provider();
    String endpoint();
    String successfulBody();

    default EvidenceQuery query() {
        return new EvidenceQuery(UUID.randomUUID(), SERVICE, "checkout", Map.of("instance", "checkout-1"),
                FROM, FROM.plusSeconds(300));
    }

    default EvidenceBudget budget() {
        return new EvidenceBudget(20, 2048, Duration.ofMinutes(15));
    }

    @Test default void rejectsWindowLargerThanBudgetWithoutCallingProvider() {
        var q = query();
        assertThatThrownBy(() -> source().capture(new EvidenceQuery(q.incidentId(), SERVICE, q.queryId(),
                q.parameters(), FROM, FROM.plusSeconds(7200)), budget()))
                .isInstanceOf(EvidenceBudgetExceeded.class);
        assertThat(provider().getAllServeEvents()).isEmpty();
    }

    @Test default void capsNormalizedOutputAndHashesCanonicalContent() {
        provider().stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody())));
        var result = source().capture(query(), budget());
        assertThat(result.serializedBytes()).isLessThanOrEqualTo(2048);
        assertThat(result.items()).hasSizeLessThanOrEqualTo(20);
        assertThat(result.contentHash()).matches("[a-f0-9]{64}");
        assertThat(result.contentHash()).isEqualTo(source().capture(query(), budget()).contentHash());
    }

    @Test default void rejectsExtremeInstantWindowWithTypedBudgetFailureBeforeRequests() {
        var q = query();
        assertThatThrownBy(() -> source().capture(new EvidenceQuery(q.incidentId(), SERVICE, q.queryId(),
                q.parameters(), Instant.MIN, Instant.MAX), budget()))
                .isInstanceOf(EvidenceBudgetExceeded.class);
        assertThat(provider().getAllServeEvents()).isEmpty();
    }

    @Test default void rejectsUnknownServiceQueryAndExtraParametersWithoutRequests() {
        var q = query();
        assertThatThrownBy(() -> source().capture(new EvidenceQuery(q.incidentId(), UUID.randomUUID(),
                q.queryId(), q.parameters(), q.from(), q.to()), budget())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source().capture(new EvidenceQuery(q.incidentId(), SERVICE,
                "http://attacker.invalid/query", q.parameters(), q.from(), q.to()), budget()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source().capture(new EvidenceQuery(q.incidentId(), SERVICE,
                q.queryId(), Map.of("instance", "checkout-1", "query", "up"), q.from(), q.to()), budget()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider().getAllServeEvents()).isEmpty();
    }

    @Test default void rejectsQueryInjectionInTypedParameter() {
        var q = query();
        assertThatThrownBy(() -> source().capture(new EvidenceQuery(q.incidentId(), SERVICE, q.queryId(),
                Map.of("instance", "x\"} or up #"), q.from(), q.to()), budget()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider().getAllServeEvents()).isEmpty();
    }

    @Test default void mapsRateLimitWithoutRetryOrLeakingBody() {
        provider().stubFor(get(urlPathEqualTo(endpoint())).willReturn(aResponse().withStatus(429)
                .withBody("secret-provider-body")));
        assertThatThrownBy(() -> source().capture(query(), budget()))
                .isInstanceOf(EvidenceSourceRateLimited.class).hasMessageNotContaining("secret-provider-body");
        provider().verify(1, getRequestedFor(urlPathEqualTo(endpoint())));
    }

    @Test default void doesNotRetryAuthorizationFailures() {
        for (int status : new int[] {400, 401, 403}) {
            provider().resetAll();
            provider().stubFor(get(urlPathEqualTo(endpoint())).willReturn(aResponse().withStatus(status)));
            assertThatThrownBy(() -> source().capture(query(), budget())).isInstanceOf(EvidenceSourceException.class);
            provider().verify(1, getRequestedFor(urlPathEqualTo(endpoint())));
        }
    }

    @Test default void retriesTransientFailureOnlyOnce() {
        provider().stubFor(get(urlPathEqualTo(endpoint())).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> source().capture(query(), budget())).isInstanceOf(EvidenceSourceException.class);
        provider().verify(2, getRequestedFor(urlPathEqualTo(endpoint())));
    }

    @Test default void rejectsOversizedResponseBeforeNormalization() {
        provider().stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(" ".repeat(70_000))));
        assertThatThrownBy(() -> source().capture(query(), budget())).isInstanceOf(EvidenceBudgetExceeded.class);
        provider().verify(1, getRequestedFor(urlPathEqualTo(endpoint())));
    }

    @Test default void neverFollowsRedirects() {
        var attacker = new WireMockServer(0);
        try {
            attacker.start();
            provider().stubFor(get(urlPathEqualTo(endpoint())).willReturn(aResponse().withStatus(302)
                    .withHeader("Location", attacker.baseUrl() + "/stolen")));
            assertThatThrownBy(() -> source().capture(query(), budget())).isInstanceOf(EvidenceSourceException.class);
            assertThat(attacker.getAllServeEvents()).isEmpty();
        } finally {
            attacker.stop();
        }
    }

    @Test default void rejectsInvalidJsonWithoutEchoingProviderContent() {
        provider().stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson("{secret-provider-body")));
        assertThatThrownBy(() -> source().capture(query(), budget()))
                .isInstanceOf(EvidenceSourceException.class).hasMessageNotContaining("secret-provider-body");
    }

    @Test default void refusesErrorEnvelopesAndMalformedSuccessShapes() {
        for (String body : new String[] {
                "{\"status\":\"error\",\"error\":\"secret-provider-body\"}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"wrong\",\"result\":[]}}",
                "{\"status\":\"success\",\"data\":{}}"}) {
            provider().stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(body)));
            assertThatThrownBy(() -> source().capture(query(), budget()))
                    .isInstanceOf(EvidenceSourceException.class)
                    .hasMessageNotContaining("secret-provider-body");
        }
    }

    @Test default void keepsCanonicalHashIndependentOfQueryMapAndLabelsOrder() {
        provider().stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody())));
        var first = source().capture(query(), budget());
        provider().stubFor(get(urlPathEqualTo(endpoint())).willReturn(okJson(successfulBody()
                .replace("\"instance\":\"checkout-1\",\"secret\":\"not-allowed\"",
                         "\"secret\":\"not-allowed\",\"instance\":\"checkout-1\""))));
        assertThat(source().capture(query(), budget()).contentHash()).isEqualTo(first.contentHash());
        assertThatThrownBy(() -> first.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.items().getFirst().labels().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test default void rejectsUnboundedCallerBudgets() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new EvidenceBudget(Integer.MAX_VALUE, 2048, Duration.ofMinutes(15)));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new EvidenceBudget(20, Integer.MAX_VALUE, Duration.ofMinutes(15)));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new EvidenceBudget(20, 2048, Duration.ofSeconds(Long.MAX_VALUE)));
        assertThat(provider().getAllServeEvents()).isEmpty();
    }
}
