package io.sentinelops.api.identity.adapter.in.security;

import io.sentinelops.api.identity.application.CurrentPrincipal;
import java.util.LinkedHashSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public final class SentinelJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String EXECUTOR_AUTHORITY = "SENTINELOPS_INTERNAL_EXECUTOR";
    public static final String API_AUTHORITY = "SENTINELOPS_API_AUDIENCE";

    private final String apiAudience;

    public SentinelJwtAuthenticationConverter(
            @Value("${sentinelops.security.audience}") String apiAudience) {
        this.apiAudience = apiAudience;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var principal = CurrentPrincipal.from(jwt);
        var authorities = new LinkedHashSet<SimpleGrantedAuthority>();
        if (jwt.getAudience().contains(apiAudience)) {
            authorities.add(new SimpleGrantedAuthority(API_AUTHORITY));
            principal.roles().stream()
                    .map(role -> new SimpleGrantedAuthority(role.authority()))
                    .forEach(authorities::add);
        }

        if (hasRealmRole(jwt, "sentinelops_executor")
                && jwt.getAudience().contains(apiAudience)) {
            authorities.add(new SimpleGrantedAuthority(EXECUTOR_AUTHORITY));
        }
        return new JwtAuthenticationToken(jwt, authorities, principal.subject());
    }

    private boolean hasRealmRole(Jwt jwt, String requiredRole) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof java.util.Map<?, ?> realm)) {
            return false;
        }
        Object roles = realm.get("roles");
        return roles instanceof java.util.Collection<?> values
                && values.stream().anyMatch(requiredRole::equals);
    }
}
