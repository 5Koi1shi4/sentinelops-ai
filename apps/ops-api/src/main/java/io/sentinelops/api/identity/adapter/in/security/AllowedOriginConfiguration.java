package io.sentinelops.api.identity.adapter.in.security;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
public class AllowedOriginConfiguration {
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${sentinelops.security.allowed-origins:}") String allowedOrigins) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(parseOrigins(allowedOrigins));
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match",
                "Idempotency-Key", "X-Correlation-Id"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    static List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",", -1))
                .map(String::trim)
                .map(AllowedOriginConfiguration::requireExactOrigin)
                .distinct()
                .toList();
    }

    private static String requireExactOrigin(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid CORS origin", exception);
        }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null || !value.equals(uri.toString())) {
            throw new IllegalArgumentException("CORS origins must be exact HTTP origins");
        }
        return value;
    }
}
