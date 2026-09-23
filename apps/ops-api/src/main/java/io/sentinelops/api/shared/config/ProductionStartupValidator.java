package io.sentinelops.api.shared.config;

import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
public final class ProductionStartupValidator implements InitializingBean {
    private static final Set<String> DEFAULT_PASSWORDS = Set.of(
            "sentinelops", "sentinelops-demo-db", "password", "changeme");

    private final Environment environment;

    public ProductionStartupValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate(environment);
    }

    public static void validate(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("demo", "test"))
                || environment.getProperty("sentinelops.demo-mode", Boolean.class, false)
                || hasText(environment.getProperty("sentinelops.demo.users"))) {
            throw new IllegalStateException("Production cannot enable Demo mode or users");
        }
        if ("deterministic".equals(environment.getProperty("sentinelops.ai.provider", "deterministic"))) {
            throw new IllegalStateException("Production requires a real or manual-only AI provider");
        }
        var origins = environment.getProperty("sentinelops.security.allowed-origins", "");
        if (origins.isBlank()) {
            throw new IllegalStateException("Production requires explicit browser origins");
        }
        for (String origin : List.of(origins.split(",", -1))) {
            var uri = requireHttps(origin.trim(), "browser origin");
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
                throw new IllegalStateException("Browser origin must not include a path");
            }
        }
        var issuer = environment.getProperty("sentinelops.security.issuer");
        requireHttps(issuer, "OIDC issuer");
        var issuerUri = environment.getProperty(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri");
        if (hasText(issuerUri)) {
            requireHttps(issuerUri, "OIDC issuer URI");
            if (!issuer.equals(issuerUri)) {
                throw new IllegalStateException("OIDC issuer settings must match");
            }
        }
        requireHttps(environment.getProperty(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri"), "OIDC JWKS URL");
        var password = environment.getProperty("spring.datasource.password");
        if (!hasText(password) || DEFAULT_PASSWORDS.contains(password.trim().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("Production database password is missing or a default");
        }
        if (hasText(environment.getProperty("sentinelops.execution-ticket.private-jwk"))) {
            throw new IllegalStateException("Production forbids an inline execution signing key");
        }
        SecretReference.resolve(environment,
                environment.getProperty("sentinelops.execution-ticket.private-jwk-secret-ref"),
                "Execution signing key");
    }

    private static URI requireHttps(String value, String purpose) {
        URI uri;
        try { uri = URI.create(value == null ? "" : value); }
        catch (IllegalArgumentException exception) {
            throw new IllegalStateException(purpose + " must be an HTTPS URL", exception);
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalStateException(purpose + " must be an HTTPS URL");
        }
        return uri;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
