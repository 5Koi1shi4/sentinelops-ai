package io.sentinelops.demo.security;

import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String audience;

    public AudienceValidator(String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("audience must not be blank");
        }
        this.audience = audience.trim();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Objects.requireNonNull(token, "token");
        var tokenAudience = token.getAudience();
        if (tokenAudience != null && tokenAudience.contains(audience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "The token audience is not accepted by the Demo service",
                null));
    }
}
