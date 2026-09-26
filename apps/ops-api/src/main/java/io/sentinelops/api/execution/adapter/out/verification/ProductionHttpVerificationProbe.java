package io.sentinelops.api.execution.adapter.out.verification;

import io.sentinelops.api.execution.application.VerificationProbe;
import io.sentinelops.api.execution.application.VerificationProbe.VerificationResult;
import io.sentinelops.api.execution.application.VerificationProbe.VerificationSpec;
import java.net.URI;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Fixed, operator-configured HTTPS readiness check for the approved production HTTP action. */
@Component
@Profile("production")
public final class ProductionHttpVerificationProbe implements VerificationProbe {

    private static final String PROBE = "production_checkout_health";
    private static final String TARGET = "checkout";

    private final RestClient client;
    private final URI healthUri;

    @Autowired
    public ProductionHttpVerificationProbe(
            RestClient.Builder builder,
            @Value("${SENTINELOPS_PROD_CHECKOUT_HEALTH_URL}") String healthUrl) {
        this(configuredClient(builder), requiredHealthUri(healthUrl));
    }

    public ProductionHttpVerificationProbe(RestClient client, URI healthUri) {
        this.client = Objects.requireNonNull(client, "client");
        this.healthUri = requiredHealthUri(Objects.requireNonNull(healthUri, "healthUri").toString());
    }

    @Override
    public VerificationResult verify(VerificationSpec specification) {
        Objects.requireNonNull(specification, "specification");
        if (!PROBE.equals(specification.probe()) || !TARGET.equals(specification.targetAlias())) {
            return VerificationResult.failed(Map.of("errorCode", "unsupported_verification_probe"));
        }
        try {
            var response = client.get().uri(healthUri).retrieve().toEntity(HealthResponse.class);
            var body = response.getBody();
            boolean up = body != null && "UP".equalsIgnoreCase(body.status());
            return new VerificationResult(up, Map.of(
                    "status", up ? "UP" : "UNKNOWN",
                    "httpStatus", response.getStatusCode().value()));
        } catch (RestClientResponseException failure) {
            return VerificationResult.failed(Map.of(
                    "status", "DOWN", "httpStatus", failure.getStatusCode().value()));
        } catch (ResourceAccessException failure) {
            return VerificationResult.failed(Map.of(
                    "status", "UNREACHABLE", "errorCode", "verification_target_unavailable"));
        } catch (RestClientException failure) {
            return VerificationResult.failed(Map.of(
                    "status", "INVALID", "errorCode", "verification_response_invalid"));
        }
    }

    private static RestClient configuredClient(RestClient.Builder builder) {
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(requestFactory).build();
    }

    private static URI requiredHealthUri(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Production health URL must be fixed HTTPS", invalid);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawPath() == null
                || uri.getRawPath().isBlank() || uri.getRawPath().contains("..")
                || uri.getRawPath().contains("%")) {
            throw new IllegalArgumentException("Production health URL must be fixed HTTPS");
        }
        return uri;
    }

    private record HealthResponse(String status) {}
}
