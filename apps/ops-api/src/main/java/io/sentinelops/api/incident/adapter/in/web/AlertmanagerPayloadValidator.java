package io.sentinelops.api.incident.adapter.in.web;

import io.sentinelops.api.shared.problem.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class AlertmanagerPayloadValidator {

    private static final int MAX_ALERTS = 200;
    private static final int MAX_LABELS = 100;
    private static final int MAX_ANNOTATIONS = 64;
    private static final int MAX_TOTAL_ANNOTATION_BYTES = 64 * 1024;
    private static final Set<String> STATUSES = Set.of("firing", "resolved");
    private static final Set<String> SEVERITIES = Set.of("sev1", "sev2", "sev3", "sev4");

    public void validate(JsonNode payload) {
        requireObject(payload, "payload");
        if (!"4".equals(requireText(payload, "version", 8))) {
            throw invalid("version must be Alertmanager webhook version 4");
        }
        requireStatus(payload, "status");
        validateOptionalText(payload, "eventId", 512);
        validateOptionalText(payload, "groupKey", 2048);
        validateOptionalText(payload, "receiver", 256);
        validateOptionalText(payload, "externalURL", 2048);

        var commonLabels = requireObject(payload.path("commonLabels"), "commonLabels");
        validateStringMap(commonLabels, "commonLabels", MAX_LABELS, 4096);
        if (payload.has("groupLabels")) {
            validateStringMap(requireObject(payload.path("groupLabels"), "groupLabels"),
                    "groupLabels", MAX_LABELS, 4096);
        }
        requireText(commonLabels, "service_key", 128);
        var severity = requireText(commonLabels, "severity", 16).toLowerCase(java.util.Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            throw invalid("commonLabels.severity is invalid");
        }
        validateOptionalText(commonLabels, "incident_fingerprint", 512);

        long annotationBytes = 0;
        if (payload.has("commonAnnotations")) {
            annotationBytes += validateStringMap(
                    requireObject(payload.path("commonAnnotations"), "commonAnnotations"),
                    "commonAnnotations",
                    MAX_ANNOTATIONS,
                    8192);
        }

        var alerts = payload.path("alerts");
        if (alerts.isArray() && alerts.size() > MAX_ALERTS) {
            throw new ApiProblemException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "WEBHOOK_ALERT_LIMIT", "Webhook contains too many alerts.");
        }
        if (!alerts.isArray() || alerts.isEmpty()) {
            throw invalid("alerts must contain between 1 and " + MAX_ALERTS + " items");
        }
        for (int index = 0; index < alerts.size(); index++) {
            var alert = requireObject(alerts.get(index), "alerts[" + index + "]");
            requireStatus(alert, "status");
            validateStringMap(
                    requireObject(alert.path("labels"), "alerts[" + index + "].labels"),
                    "alerts[" + index + "].labels",
                    MAX_LABELS,
                    4096);
            if (alert.has("annotations")) {
                annotationBytes += validateStringMap(
                        requireObject(
                                alert.path("annotations"),
                                "alerts[" + index + "].annotations"),
                        "alerts[" + index + "].annotations",
                        MAX_ANNOTATIONS,
                        8192);
            }
            validateOptionalText(alert, "startsAt", 128);
            validateOptionalText(alert, "endsAt", 128);
            validateOptionalText(alert, "generatorURL", 2048);
            validateOptionalText(alert, "fingerprint", 512);
        }
        if (annotationBytes > MAX_TOTAL_ANNOTATION_BYTES) {
            throw invalid("annotations exceed 64 KiB");
        }
    }

    private JsonNode requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw invalid(field + " must be an object");
        }
        return node;
    }

    private void requireStatus(JsonNode object, String field) {
        var status = requireText(object, field, 16).toLowerCase(java.util.Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw invalid(field + " must be firing or resolved");
        }
    }

    private String requireText(JsonNode object, String field, int maxLength) {
        if (!object.hasNonNull(field) || !object.path(field).isString()) {
            throw invalid(field + " must be a string");
        }
        var value = object.path(field).stringValue();
        if (value.isBlank() || value.length() > maxLength) {
            throw invalid(field + " must contain between 1 and " + maxLength + " characters");
        }
        return value;
    }

    private void validateOptionalText(JsonNode object, String field, int maxLength) {
        if (object.has(field)) {
            requireText(object, field, maxLength);
        }
    }

    private long validateStringMap(
            JsonNode object, String field, int maxProperties, int maxValueLength) {
        if (object.size() > maxProperties) {
            throw invalid(field + " has too many properties");
        }
        long totalBytes = 0;
        for (var entry : object.properties()) {
            var value = entry.getValue();
            if (!value.isString()
                    || value.stringValue().getBytes(StandardCharsets.UTF_8).length > maxValueLength) {
                throw invalid(field + " values must be bounded strings");
            }
            totalBytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            totalBytes += value.stringValue().getBytes(StandardCharsets.UTF_8).length;
        }
        return totalBytes;
    }

    private IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Invalid Alertmanager payload: " + detail);
    }
}
