package io.sentinelops.api.shared.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
        requireSecureDataConnections(environment);
        if (hasText(environment.getProperty("sentinelops.execution-ticket.private-jwk"))) {
            throw new IllegalStateException("Production forbids an inline execution signing key");
        }
        SecretReference.resolve(environment,
                environment.getProperty("sentinelops.execution-ticket.private-jwk-secret-ref"),
                "Execution signing key");
    }

    private static void requireSecureDataConnections(Environment environment) {
        String jdbcUrl = environment.getProperty("spring.datasource.url");
        URI database;
        try {
            if (!hasText(jdbcUrl) || !jdbcUrl.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException("not a PostgreSQL JDBC URL");
            }
            database = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Production requires an explicit PostgreSQL JDBC URL", invalid);
        }
        if (database.getHost() == null || database.getRawUserInfo() != null
                || database.getRawFragment() != null || database.getRawPath() == null
                || database.getRawPath().length() < 2) {
            throw new IllegalStateException("Production PostgreSQL URL must name a database without inline credentials");
        }
        if (hasInlinePostgresCredentials(database.getRawQuery())) {
            throw new IllegalStateException("Production PostgreSQL URL must not contain inline credentials");
        }
        if (!"postgres".equalsIgnoreCase(database.getHost())
                && !hasVerifiedPostgresTls(database.getRawQuery())) {
            throw new IllegalStateException("External PostgreSQL requires verified TLS without validation bypasses");
        }

        String redisUrl = environment.getProperty("spring.data.redis.url");
        if (hasText(redisUrl)) {
            throw new IllegalStateException("Production Valkey must use separate host and secret settings");
        }
        String redisHost = environment.getProperty("spring.data.redis.host");
        if (!hasText(redisHost)) {
            throw new IllegalStateException("Production requires an explicit Valkey host");
        }
        boolean redisTls = environment.getProperty("spring.data.redis.ssl.enabled", Boolean.class, false);
        if (!"valkey".equalsIgnoreCase(redisHost) && !redisTls) {
            throw new IllegalStateException("External Valkey requires TLS");
        }
    }

    private static boolean hasVerifiedPostgresTls(String rawQuery) {
        if (!hasText(rawQuery)) {
            return false;
        }
        int modes = 0;
        int factories = 0;
        boolean verified = false;
        for (String pair : rawQuery.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String key;
            String value;
            try {
                key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            switch (key) {
                case "sslmode" -> {
                    modes++;
                    verified = "verify-full".equalsIgnoreCase(value);
                }
                case "sslfactory" -> {
                    factories++;
                    if (factories > 1 || !("org.postgresql.ssl.LibPQFactory".equals(value)
                            || "org.postgresql.ssl.DefaultJavaSSLFactory".equals(value))) {
                        return false;
                    }
                }
                case "sslhostnameverifier", "sslfactoryarg", "ssl", "user", "password" -> {
                    return false;
                }
                default -> { }
            }
        }
        return modes == 1 && verified;
    }

    private static boolean hasInlinePostgresCredentials(String rawQuery) {
        if (!hasText(rawQuery)) {
            return false;
        }
        for (String pair : rawQuery.split("&", -1)) {
            String key;
            try {
                key = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException invalid) {
                return true;
            }
            if ("user".equals(key) || "password".equals(key)) {
                return true;
            }
        }
        return false;
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
