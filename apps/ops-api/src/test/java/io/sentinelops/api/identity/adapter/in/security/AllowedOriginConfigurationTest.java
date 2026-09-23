package io.sentinelops.api.identity.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AllowedOriginConfigurationTest {
    @Test
    void acceptsOnlyConfiguredExactOriginsWithoutCookies() {
        var source = new AllowedOriginConfiguration().corsConfigurationSource(
                "https://console.example.test,https://review.example.test");
        var request = new MockHttpServletRequest("OPTIONS", "/api/v1/incidents");
        var cors = source.getCorsConfiguration(request);
        assertThat(cors).isNotNull();
        assertThat(cors.checkOrigin("https://console.example.test"))
                .isEqualTo("https://console.example.test");
        assertThat(cors.checkOrigin("https://console.example.test.evil.test")).isNull();
        assertThat(cors.getAllowCredentials()).isFalse();
        assertThat(cors.checkHeaders(java.util.List.of("Authorization", "Content-Type",
                "If-Match", "Idempotency-Key")))
                .containsExactly("Authorization", "Content-Type", "If-Match", "Idempotency-Key");
    }

    @Test
    void rejectsWildcardPathsAndMalformedOrigins() {
        assertThatThrownBy(() -> AllowedOriginConfiguration.parseOrigins("*"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllowedOriginConfiguration.parseOrigins(
                "https://console.example.test/path"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllowedOriginConfiguration.parseOrigins(
                "https://console.example.test,"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
