package io.sentinelops.api.identity.adapter.in.security;

import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2TokenValidatorResult INVALID = OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Token audience is not trusted", null));

    private final String apiAudience;
    private final String executorAudience;

    public AudienceValidator(String apiAudience, String executorAudience) {
        this.apiAudience = Objects.requireNonNull(apiAudience);
        this.executorAudience = Objects.requireNonNull(executorAudience);
        if (apiAudience.isBlank() || executorAudience.isBlank()
                || apiAudience.equals(executorAudience)) {
            throw new IllegalArgumentException("API and Executor audiences must be distinct");
        }
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        var audiences = token.getAudience();
        if (audiences == null) return INVALID;
        boolean api = audiences.contains(apiAudience);
        boolean executor = audiences.contains(executorAudience);
        return api != executor ? OAuth2TokenValidatorResult.success() : INVALID;
    }
}
