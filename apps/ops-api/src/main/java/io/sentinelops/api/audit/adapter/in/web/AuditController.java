package io.sentinelops.api.audit.adapter.in.web;

import io.sentinelops.api.audit.application.AuditService;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-records")
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) { this.audit = audit; }

    @GetMapping
    AuditService.AuditPage list(@RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return audit.list(CurrentPrincipal.from(jwt), serviceId, before, limit);
    }
}
