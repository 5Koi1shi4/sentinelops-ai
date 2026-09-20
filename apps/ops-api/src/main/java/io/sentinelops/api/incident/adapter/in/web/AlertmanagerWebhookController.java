package io.sentinelops.api.incident.adapter.in.web;

import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.incident.application.IncidentSummary;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/integrations/alertmanager")
public class AlertmanagerWebhookController {

    private static final String DEFAULT_SOURCE = "alertmanager";

    private final IncidentApplicationService incidents;
    private final AlertmanagerPayloadValidator validator;

    public AlertmanagerWebhookController(
            IncidentApplicationService incidents, AlertmanagerPayloadValidator validator) {
        this.incidents = incidents;
        this.validator = validator;
    }

    @PostMapping("/webhook")
    ResponseEntity<IncidentSummary> ingest(
            @RequestHeader(value = "X-SentinelOps-Source", defaultValue = DEFAULT_SOURCE)
                    String source,
            @RequestHeader(value = "X-SentinelOps-Event-Id", required = false)
                    String sourceEventId,
            @RequestBody JsonNode payload) {
        validator.validate(payload);
        var commonLabels = payload.path("commonLabels");
        var commonAnnotations = payload.path("commonAnnotations");
        var firstAlert = firstAlert(payload);
        var labels = commonLabels.isObject() ? commonLabels : firstAlert.path("labels");
        var annotations =
                commonAnnotations.isObject() ? commonAnnotations : firstAlert.path("annotations");

        var serviceKey = requiredText(labels, "service_key");
        var severity = requiredText(labels, "severity");
        var fingerprint = firstNonBlank(
                text(labels, "incident_fingerprint"),
                text(firstAlert, "fingerprint"),
                text(payload, "groupKey"));
        if (fingerprint == null) {
            throw new IllegalArgumentException("Alertmanager payload has no incident fingerprint");
        }
        var title = firstNonBlank(
                text(annotations, "summary"),
                text(labels, "alertname"),
                "Alertmanager incident");
        var payloadEventId = firstNonBlank(sourceEventId, text(payload, "eventId"));
        var alertStatus = parseStatus(requiredText(payload, "status"));

        var summary = incidents.ingest(new AlertEnvelope(
                source,
                payloadEventId,
                serviceKey,
                fingerprint,
                title,
                severity,
                alertStatus,
                payload));
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, URI.create("/api/v1/incidents/" + summary.id()).toString())
                .eTag('"' + Long.toString(summary.version()) + '"')
                .body(summary);
    }

    private JsonNode firstAlert(JsonNode payload) {
        var alerts = payload.path("alerts");
        return alerts.isArray() && !alerts.isEmpty() ? alerts.get(0) : payload;
    }

    private AlertEnvelope.AlertStatus parseStatus(String status) {
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "firing" -> AlertEnvelope.AlertStatus.FIRING;
            case "resolved" -> AlertEnvelope.AlertStatus.RESOLVED;
            default -> throw new IllegalArgumentException("Unsupported Alertmanager status");
        };
    }

    private String requiredText(JsonNode node, String field) {
        var value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Alertmanager payload is missing " + field);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        var value = node.path(field).asString();
        return value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... candidates) {
        for (var candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }
}
