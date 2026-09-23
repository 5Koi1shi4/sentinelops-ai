package io.sentinelops.api.incident.adapter.in.web;

import io.sentinelops.api.incident.adapter.in.webhook.BoundedWebhookRequest;
import io.sentinelops.api.incident.adapter.in.webhook.WebhookRateLimiter;
import io.sentinelops.api.incident.adapter.in.webhook.WebhookReplayGuard;
import io.sentinelops.api.incident.adapter.in.webhook.WebhookSignatureVerifier;
import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.incident.application.IncidentSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/integrations/alertmanager")
public class AlertmanagerWebhookController {

    private final IncidentApplicationService incidents;
    private final AlertmanagerPayloadValidator validator;
    private final BoundedWebhookRequest bounded;
    private final WebhookSignatureVerifier signatures;
    private final WebhookReplayGuard replay;
    private final WebhookRateLimiter rates;
    private final ObjectMapper mapper;

    public AlertmanagerWebhookController(
            IncidentApplicationService incidents, AlertmanagerPayloadValidator validator,
            BoundedWebhookRequest bounded, WebhookSignatureVerifier signatures,
            WebhookReplayGuard replay, WebhookRateLimiter rates, ObjectMapper mapper) {
        this.incidents = incidents;
        this.validator = validator;
        this.bounded = bounded;
        this.signatures = signatures;
        this.replay = replay;
        this.rates = rates;
        this.mapper = mapper;
    }

    @PostMapping("/webhook")
    ResponseEntity<IncidentSummary> ingest(
            HttpServletRequest request,
            @RequestHeader(value = "X-Sentinel-Source", required = false) String source,
            @RequestHeader(value = "X-Sentinel-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Sentinel-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Sentinel-Signature", required = false) String signature,
            @RequestHeader(value = "X-SentinelOps-Event-Id", required = false)
                    String sourceEventId) {
        byte[] body = bounded.read(request);
        signatures.verify(source, timestamp, nonce, signature, body);
        replay.claim(source, nonce);
        rates.acquire(source);
        JsonNode payload = bounded.parse(body, request.getContentType(), mapper);
        validator.validate(payload);
        if (sourceEventId != null && sourceEventId.length() > 512) {
            throw new IllegalArgumentException("Webhook event ID is too long");
        }
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
