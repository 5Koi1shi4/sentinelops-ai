package io.sentinelops.api.incident.adapter.in.web;

import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.incident.application.IncidentCockpitQueryService;
import io.sentinelops.api.incident.application.IncidentCockpitView;
import io.sentinelops.api.incident.application.IncidentPage;
import io.sentinelops.api.incident.application.IncidentTimelinePage;
import io.sentinelops.api.incident.domain.IncidentStatus;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentQueryController {

    private final IncidentApplicationService incidents;
    private final IncidentCockpitQueryService cockpit;

    public IncidentQueryController(
            IncidentApplicationService incidents, IncidentCockpitQueryService cockpit) {
        this.incidents = incidents;
        this.cockpit = cockpit;
    }

    @GetMapping
    IncidentPage list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            @AuthenticationPrincipal Jwt jwt) {
        var statusFilter = status == null ? null : IncidentStatus.fromDatabase(status);
        return incidents.list(
                statusFilter,
                serviceId,
                severity,
                cursor,
                pageSize,
                CurrentPrincipal.from(jwt));
    }

    @GetMapping("/{id}")
    ResponseEntity<IncidentCockpitView> detail(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var view = cockpit.get(id, CurrentPrincipal.from(jwt));
        return ResponseEntity.ok()
                .eTag('"' + Long.toString(view.incident().version()) + '"')
                .body(view);
    }

    @GetMapping("/{id}/timeline")
    IncidentTimelinePage timeline(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            @AuthenticationPrincipal Jwt jwt) {
        return incidents.timeline(id, cursor, pageSize, CurrentPrincipal.from(jwt));
    }
}
