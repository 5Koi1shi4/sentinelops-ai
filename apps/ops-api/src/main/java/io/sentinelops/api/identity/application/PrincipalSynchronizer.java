package io.sentinelops.api.identity.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.sentinelops.api.identity.adapter.out.persistence.PrincipalStore;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class PrincipalSynchronizer {
    private final PrincipalStore store;
    private final MeterRegistry metrics;
    private final String trustedIssuer;

    public PrincipalSynchronizer(PrincipalStore store, MeterRegistry metrics,
            @Value("${sentinelops.security.issuer}") String trustedIssuer) {
        this.store = store;
        this.metrics = metrics;
        this.trustedIssuer = trustedIssuer;
    }

    public UUID synchronize(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt");
        if (jwt.getIssuer() == null || !trustedIssuer.equals(jwt.getIssuer().toString())) {
            throw new SecurityException("Untrusted token issuer");
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 256) {
            throw new SecurityException("Invalid token subject");
        }
        var roles = new LinkedHashSet<PlatformRole>();
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> realm && realm.get("roles") instanceof Collection<?> claims) {
            for (Object value : claims) {
                if (!(value instanceof String name)) continue;
                var recognized = PlatformRole.fromClaim(name);
                if (recognized.isPresent()) roles.add(recognized.orElseThrow());
                else metrics.counter("sentinelops.identity.unknown_roles").increment();
            }
        }
        var services = new LinkedHashSet<UUID>();
        Object serviceClaim = jwt.getClaim("service_ids");
        if (serviceClaim instanceof Collection<?> claims) {
            if (claims.size() > 100) throw new SecurityException("Too many service scopes");
            for (Object value : claims) {
                if (!(value instanceof String id)) continue;
                try { services.add(UUID.fromString(id)); }
                catch (IllegalArgumentException ignored) { /* 无效服务范围不授予访问权限。 */ }
            }
        }
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) name = subject;
        if (name.length() > 200) name = name.substring(0, 200);
        Instant issuedAt = jwt.getIssuedAt() == null ? Instant.EPOCH
                : jwt.getIssuedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return store.reconcile(jwt.getIssuer().toString(), subject, name, issuedAt, roles, services);
    }
}
