package io.sentinelops.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.sentinelops.api.execution.adapter.out.verification.DemoHttpVerificationProbe;
import io.sentinelops.api.execution.application.VerificationProbe.VerificationSpec;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DemoHttpVerificationProbeTest {

    private MockRestServiceServer server;
    private DemoHttpVerificationProbe probe;

    @BeforeEach
    void createProbe() {
        var builder = RestClient.builder().baseUrl("https://configured-demo.test");
        server = MockRestServiceServer.bindTo(builder).build();
        probe = new DemoHttpVerificationProbe(builder.build());
    }

    @Test
    void verifiesReadinessAgainstOnlyTheConfiguredTarget() {
        server.expect(requestTo("https://configured-demo.test/actuator/health/readiness"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"UP\",\"components\":{}}", MediaType.APPLICATION_JSON));

        var result = probe.verify(specification("demo_checkout_health", "demo-checkout"));

        assertThat(result.successful()).isTrue();
        assertThat(result.sanitizedResult())
                .containsEntry("status", "UP")
                .containsEntry("httpStatus", 200);
        server.verify();
    }

    @Test
    void sanitizesFailureAndRejectsUnknownProbeWithoutNetworkTraffic() {
        server.expect(requestTo("https://configured-demo.test/actuator/health/readiness"))
                .andRespond(withServerError());

        var failed = probe.verify(specification("demo_checkout_health", "demo-checkout"));

        assertThat(failed.successful()).isFalse();
        assertThat(failed.sanitizedResult())
                .containsEntry("status", "DOWN")
                .containsEntry("httpStatus", 500)
                .doesNotContainKeys("body", "headers");
        server.verify();

        var unsupported = probe.verify(specification("arbitrary_http", "attacker-host"));
        assertThat(unsupported.successful()).isFalse();
        assertThat(unsupported.sanitizedResult())
                .containsEntry("errorCode", "unsupported_verification_probe");
    }

    private VerificationSpec specification(String probeName, String targetAlias) {
        return new VerificationSpec(
                UUID.randomUUID(), probeName, targetAlias, BigDecimal.ONE);
    }
}
