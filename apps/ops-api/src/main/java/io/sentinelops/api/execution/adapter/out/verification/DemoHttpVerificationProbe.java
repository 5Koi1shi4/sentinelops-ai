package io.sentinelops.api.execution.adapter.out.verification;

import io.sentinelops.api.execution.application.VerificationProbe;
import io.sentinelops.api.execution.application.VerificationProbe.VerificationResult;
import io.sentinelops.api.execution.application.VerificationProbe.VerificationSpec;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile("!production")
public final class DemoHttpVerificationProbe implements VerificationProbe {

    private static final String PROBE = "demo_checkout_health";
    private static final String TARGET = "demo-checkout";

    private final RestClient client;

    @Autowired
    public DemoHttpVerificationProbe(
            RestClient.Builder builder,
            @Value("${sentinelops.verification.demo-checkout-base-url}") String baseUrl) {
        this(configuredClient(builder, baseUrl));
    }

    public DemoHttpVerificationProbe(RestClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public VerificationResult verify(VerificationSpec specification) {
        Objects.requireNonNull(specification, "specification");
        if (!PROBE.equals(specification.probe())
                || !TARGET.equals(specification.targetAlias())) {
            return VerificationResult.failed(Map.of(
                    "errorCode", "unsupported_verification_probe"));
        }
        try {
            var response = client.get()
                    .uri("/actuator/health/readiness")
                    .retrieve()
                    .toEntity(HealthResponse.class);
            var body = response.getBody();
            boolean up = body != null && "UP".equalsIgnoreCase(body.status());
            return new VerificationResult(
                    up,
                    Map.of(
                            "status", up ? "UP" : "UNKNOWN",
                            "httpStatus", response.getStatusCode().value()));
        } catch (RestClientResponseException failure) {
            return VerificationResult.failed(Map.of(
                    "status", "DOWN",
                    "httpStatus", failure.getStatusCode().value()));
        } catch (ResourceAccessException failure) {
            return VerificationResult.failed(Map.of(
                    "status", "UNREACHABLE",
                    "errorCode", "verification_target_unavailable"));
        }
    }

    private static RestClient configuredClient(
            RestClient.Builder builder, String baseUrl) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(requestFactory)
                .baseUrl(requireText(baseUrl, "baseUrl"))
                .build();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record HealthResponse(String status) {}
}
