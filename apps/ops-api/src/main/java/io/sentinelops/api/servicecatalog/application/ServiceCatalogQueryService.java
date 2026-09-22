package io.sentinelops.api.servicecatalog.application;

import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.servicecatalog.adapter.out.persistence.ServiceCatalogQueryStore;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ServiceCatalogQueryService {
    private final ServiceCatalogQueryStore store;

    public ServiceCatalogQueryService(ServiceCatalogQueryStore store) {
        this.store = store;
    }

    public List<ServiceSummary> list(String afterKey, int limit, CurrentPrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        if (!principal.hasAnyRole(PlatformRole.values())) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "access_denied", "The principal cannot view the service catalog.");
        }
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("invalid service page");
        String cursor = normalizeCursor(afterKey);
        boolean platformAdmin = principal.hasAnyRole(PlatformRole.PLATFORM_ADMIN);
        return store.list(cursor, limit, platformAdmin, principal.serviceIds()).stream()
                .filter(service -> principal.canAccess(service.id()))
                .toList();
    }

    private static String normalizeCursor(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.length() > 256 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid service cursor");
        }
        return value;
    }

    public record ServiceSummary(UUID id, String serviceKey, String displayName, String ownerTeam) {}
}
