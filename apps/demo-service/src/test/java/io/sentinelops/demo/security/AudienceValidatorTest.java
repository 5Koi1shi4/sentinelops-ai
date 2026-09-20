package io.sentinelops.demo.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("demo-service");

    @Test
    void acceptsOnlyTheConfiguredAudience() {
        assertThat(validator.validate(jwt(List.of("demo-service"))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt(List.of("ops-api"))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(List.of())).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
    }

    private Jwt jwt(List<String> audience) {
        var builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuer("https://issuer.sentinelops.test")
                .subject("test-principal")
                .issuedAt(Instant.parse("2026-09-20T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-20T01:00:00Z"));
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }
}
