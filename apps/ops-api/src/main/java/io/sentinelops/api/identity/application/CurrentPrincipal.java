package io.sentinelops.api.identity.application;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public record CurrentPrincipal(
        String issuer,
        String subject,
        Set<PlatformRole> roles,
        Set<UUID> serviceIds) {

    public CurrentPrincipal {
        issuer = requireText(issuer, "issuer");
        subject = requireText(subject, "subject");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        serviceIds = Set.copyOf(Objects.requireNonNull(serviceIds, "serviceIds"));
    }

    public static CurrentPrincipal from(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt");
        var issuer = jwt.getIssuer();
        var roles = new LinkedHashSet<PlatformRole>();
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> realm) {
            Object roleValues = realm.get("roles");
            if (roleValues instanceof Collection<?> values) {
                values.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(PlatformRole::fromClaim)
                        .flatMap(Optional::stream)
                        .forEach(roles::add);
            }
        }

        var serviceIds = new LinkedHashSet<UUID>();
        Object serviceValues = jwt.getClaim("service_ids");
        if (serviceValues instanceof Collection<?> values) {
            values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .forEach(value -> parseUuid(value).ifPresent(serviceIds::add));
        }
        return new CurrentPrincipal(
                issuer == null ? null : issuer.toString(),
                jwt.getSubject(),
                roles,
                serviceIds);
    }

    public boolean canAccess(UUID serviceId) {
        return roles.contains(PlatformRole.PLATFORM_ADMIN) || serviceIds.contains(serviceId);
    }

    public boolean hasAnyRole(PlatformRole... accepted) {
        for (var role : accepted) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    public String principalKey() {
        return issuer + '\u001f' + subject;
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
