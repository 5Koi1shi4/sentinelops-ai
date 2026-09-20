package io.sentinelops.api.incident.adapter.in.web;

import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.incident.application.IncidentPage;
import io.sentinelops.api.incident.application.IncidentSummary;
import io.sentinelops.api.incident.application.IncidentTimelinePage;
import io.sentinelops.api.incident.domain.IncidentStatus;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentQueryController {

    private final IncidentApplicationService incidents;

    public IncidentQueryController(IncidentApplicationService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    IncidentPage list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize) {
        var statusFilter = status == null ? null : IncidentStatus.fromDatabase(status);
        return incidents.list(statusFilter, serviceId, severity, cursor, pageSize);
    }

    @GetMapping("/{id}")
    ResponseEntity<IncidentSummary> detail(@PathVariable UUID id) {
        var summary = incidents.get(id);
        return ResponseEntity.ok()
                .eTag('"' + Long.toString(summary.version()) + '"')
                .body(summary);
    }

    @GetMapping("/{id}/timeline")
    IncidentTimelinePage timeline(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize) {
        return incidents.timeline(id, cursor, pageSize);
    }
}
