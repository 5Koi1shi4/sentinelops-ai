package io.sentinelops.api.servicecatalog.adapter.in.web;

import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.servicecatalog.application.ServiceCatalogQueryService;
import io.sentinelops.api.servicecatalog.application.ServiceCatalogQueryService.ServiceSummary;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceCatalogQueryController {
    private final ServiceCatalogQueryService services;

    public ServiceCatalogQueryController(ServiceCatalogQueryService services) {
        this.services = services;
    }

    @GetMapping
    List<ServiceSummary> list(@RequestParam(required = false) String afterKey,
                              @RequestParam(defaultValue = "50") int limit,
                              @AuthenticationPrincipal Jwt jwt) {
        return services.list(afterKey, limit, CurrentPrincipal.from(jwt));
    }
}
