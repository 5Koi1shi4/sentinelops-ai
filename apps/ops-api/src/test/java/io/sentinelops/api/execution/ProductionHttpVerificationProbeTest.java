package io.sentinelops.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.sentinelops.api.execution.adapter.out.verification.ProductionHttpVerificationProbe;
import io.sentinelops.api.execution.application.VerificationProbe.VerificationSpec;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ProductionHttpVerificationProbeTest {

    private static final URI HEALTH_URI =
            URI.create("https://checkout.example.test/actuator/health/readiness");

    private MockRestServiceServer server;
    private ProductionHttpVerificationProbe probe;

    @BeforeEach
    void configure() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        probe = new ProductionHttpVerificationProbe(builder.build(), HEALTH_URI);
    }

    @Test
    void usesOnlyTheConfiguredHttpsHealthEndpointForTheApprovedTarget() {
        server.expect(requestTo(HEALTH_URI))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));

        var result = probe.verify(specification("production_checkout_health", "checkout"));

        assertThat(result.successful()).isTrue();
        assertThat(result.sanitizedResult())
                .containsEntry("status", "UP")
                .containsEntry("httpStatus", 200);
        server.verify();
    }

    @Test
    void refusesDemoOrUnknownTargetsWithoutSendingARequest() {
        assertThat(probe.verify(specification("demo_checkout_health", "demo-checkout"))
                .sanitizedResult()).containsEntry("errorCode", "unsupported_verification_probe");
        assertThat(probe.verify(specification("production_checkout_health", "other"))
                .sanitizedResult()).containsEntry("errorCode", "unsupported_verification_probe");
        server.verify();
    }

    @Test
    void treatsTargetFailureAsUnverifiedAndDoesNotReturnResponseBody() {
        server.expect(requestTo(HEALTH_URI)).andRespond(withServerError());

        var result = probe.verify(specification("production_checkout_health", "checkout"));

        assertThat(result.successful()).isFalse();
        assertThat(result.sanitizedResult())
                .containsEntry("status", "DOWN")
                .containsEntry("httpStatus", 500)
                .doesNotContainKeys("body", "headers");
        server.verify();
    }

    @Test
    void rejectsInsecureOrDynamicHealthEndpointsAtConfigurationTime() {
        assertThatThrownBy(() -> new ProductionHttpVerificationProbe(
                RestClient.builder(), "http://checkout.example.test/health"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductionHttpVerificationProbe(
                RestClient.builder(), "https://checkout.example.test/health?url=other"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private VerificationSpec specification(String name, String target) {
        return new VerificationSpec(UUID.randomUUID(), name, target, BigDecimal.ONE);
    }
}
