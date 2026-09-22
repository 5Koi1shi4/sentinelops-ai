package io.sentinelops.api.incident.application;

import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.incident.adapter.out.persistence.IncidentStore;
import io.sentinelops.api.incident.domain.IncidentStatus;
import io.sentinelops.api.incident.domain.IncidentCommand;
import io.sentinelops.api.incident.domain.IncidentStateMachine;
import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.shared.problem.ApiProblemException;
import io.sentinelops.api.shared.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class IncidentApplicationService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SEVERITIES = Set.of("sev1", "sev2", "sev3", "sev4");

    private final IncidentStore store;
    private final UuidV7Generator ids;
    private final TimeProvider time;
    private final ObjectMapper objectMapper;

    public IncidentApplicationService(
            IncidentStore store,
            UuidV7Generator ids,
            TimeProvider time,
            ObjectMapper objectMapper) {
        this.store = store;
        this.ids = ids;
        this.time = time;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IncidentSummary ingest(AlertEnvelope alert) {
        String sourceEventId = sourceEventId(alert);

        // Lock ordering is deliberate: delivery key, service lookup, active incident upsert.
        // No network calls are allowed while this transaction is open.
        store.lockDelivery(alert.source(), sourceEventId);
        var existingIncident = store.findIncidentIdForDelivery(alert.source(), sourceEventId);
        if (existingIncident.isPresent()) {
            return requireSummary(existingIncident.orElseThrow());
        }

        var serviceId = store.findServiceId(alert.serviceKey()).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "service_not_found",
                "No registered service matches the alert service key."));
        var now = time.now();
        var allocated = store.upsertIncident(ids.generate(), serviceId, alert, now);
        boolean recoveryDuringExecution = alert.status() == AlertEnvelope.AlertStatus.RESOLVED
                && allocated.status() == IncidentStatus.EXECUTING;
        if (alert.status() == AlertEnvelope.AlertStatus.RESOLVED
                && (allocated.status() == IncidentStatus.AWAITING_APPROVAL
                        || allocated.status() == IncidentStatus.EXECUTING)) {
            var target = IncidentStateMachine.next(
                    allocated.status(), IncidentCommand.RECEIVE_RECOVERY_SIGNAL);
            allocated = store.applyTransition(allocated, target, now);
            if (recoveryDuringExecution) {
                store.settleExecutionsForRecovery(allocated.id(), now);
            }
        }
        var eventType = alert.status() == AlertEnvelope.AlertStatus.RESOLVED
                ? "alert_recovered"
                : "alert_received";
        int inserted = store.appendSourceEvent(
                ids.generate(),
                allocated,
                eventType,
                alert,
                sourceEventId,
                objectMapper.writeValueAsString(alert.payload()),
                now);
        if (inserted != 1) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "idempotency_conflict",
                    "The source event was already associated with another request.");
        }
        var outboxPayload = objectMapper.createObjectNode()
                .put("incidentId", allocated.id().toString())
                .put("source", alert.source())
                .put("sourceEventId", sourceEventId)
                .put("status", allocated.status().databaseValue());
        store.appendOutboxEvent(
                ids.generate(),
                allocated.id(),
                "incident." + eventType.replace('_', '-'),
                objectMapper.writeValueAsString(outboxPayload),
                now);
        return requireSummary(allocated.id());
    }

    @Transactional(readOnly = true)
    public IncidentSummary get(UUID incidentId) {
        return requireSummary(incidentId);
    }

    @Transactional(readOnly = true)
    public IncidentPage list(
            IncidentStatus status,
            UUID serviceId,
            String severity,
            String cursor,
            Integer requestedPageSize,
            CurrentPrincipal principal) {
        authorizeViewerRole(principal);
        Set<UUID> visibleServiceIds = visibleServiceIds(principal, serviceId);
        if (visibleServiceIds != null && visibleServiceIds.isEmpty()) {
            return new IncidentPage(List.of(), null);
        }
        int pageSize = pageSize(requestedPageSize);
        String severityFilter = normalizeSeverity(severity);
        var decoded = decodeIncidentCursor(cursor);
        var rows = store.list(
                status,
                visibleServiceIds,
                severityFilter,
                decoded == null ? null : decoded.openedAt(),
                decoded == null ? null : decoded.id(),
                pageSize + 1);
        boolean hasNext = rows.size() > pageSize;
        List<IncidentSummary> items = hasNext ? List.copyOf(rows.subList(0, pageSize)) : rows;
        String nextCursor = hasNext ? encodeIncidentCursor(items.getLast()) : null;
        return new IncidentPage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public IncidentTimelinePage timeline(
            UUID incidentId,
            String cursor,
            Integer requestedPageSize,
            CurrentPrincipal principal) {
        authorizeViewer(principal, requireSummary(incidentId).serviceId());
        int pageSize = pageSize(requestedPageSize);
        long afterSequence = decodeTimelineCursor(cursor);
        var rows = store.timeline(incidentId, afterSequence, pageSize + 1);
        boolean hasNext = rows.size() > pageSize;
        List<IncidentTimelineItem> items =
                hasNext ? List.copyOf(rows.subList(0, pageSize)) : rows;
        String nextCursor = hasNext ? encodeTimelineCursor(items.getLast().sequence()) : null;
        return new IncidentTimelinePage(items, nextCursor);
    }

    private IncidentSummary requireSummary(UUID incidentId) {
        return store.findSummary(incidentId).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, "incident_not_found", "The incident does not exist."));
    }

    private Set<UUID> visibleServiceIds(CurrentPrincipal principal, UUID requestedServiceId) {
        if (requestedServiceId != null) {
            authorizeViewer(principal, requestedServiceId);
            return Set.of(requestedServiceId);
        }
        return principal.roles().contains(PlatformRole.PLATFORM_ADMIN)
                ? null
                : principal.serviceIds();
    }

    private void authorizeViewer(CurrentPrincipal principal, UUID serviceId) {
        authorizeViewerRole(principal);
        if (!principal.canAccess(serviceId)) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "access_denied",
                    "The principal cannot view incidents for this service.");
        }
    }

    private void authorizeViewerRole(CurrentPrincipal principal) {
        if (!principal.hasAnyRole(
                PlatformRole.OBSERVER,
                PlatformRole.ON_CALL_OPERATOR,
                PlatformRole.SRE_APPROVER,
                PlatformRole.RUNBOOK_ADMIN,
                PlatformRole.PLATFORM_ADMIN)) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "access_denied",
                    "The principal cannot view incidents.");
        }
    }

    private String sourceEventId(AlertEnvelope alert) {
        if (alert.sourceEventId() != null) {
            return alert.sourceEventId();
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(alert.source().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0x1F);
            digest.update(objectMapper.writeValueAsBytes(canonicalize(alert.payload())));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            var canonical = objectMapper.createObjectNode();
            node.properties().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> canonical.set(entry.getKey(), canonicalize(entry.getValue())));
            return canonical;
        }
        if (node.isArray()) {
            var canonical = objectMapper.createArrayNode();
            node.forEach(element -> canonical.add(canonicalize(element)));
            return canonical;
        }
        return node.deepCopy();
    }

    private int pageSize(Integer requestedPageSize) {
        if (requestedPageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (requestedPageSize <= 0) {
            throw invalidCursorOrPage("pageSize must be greater than zero");
        }
        return Math.min(requestedPageSize, MAX_PAGE_SIZE);
    }

    private String encodeIncidentCursor(IncidentSummary lastItem) {
        var cursor = objectMapper.createObjectNode()
                .put("openedAt", lastItem.openedAt().toString())
                .put("id", lastItem.id().toString());
        return encode(cursor);
    }

    private IncidentCursor decodeIncidentCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var decoded = decode(cursor);
            return new IncidentCursor(
                    Instant.parse(decoded.required("openedAt").asString()),
                    UUID.fromString(decoded.required("id").asString()));
        } catch (RuntimeException failure) {
            throw invalidCursorOrPage("cursor is invalid");
        }
    }

    private String encodeTimelineCursor(long sequence) {
        return encode(objectMapper.createObjectNode().put("sequence", sequence));
    }

    private long decodeTimelineCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            var sequenceNode = decode(cursor).required("sequence");
            if (!sequenceNode.isIntegralNumber() || !sequenceNode.canConvertToLong()) {
                throw new IllegalArgumentException("timeline sequence must be an integer");
            }
            long sequence = sequenceNode.longValue();
            if (sequence < 0) {
                throw new IllegalArgumentException("negative timeline sequence");
            }
            return sequence;
        } catch (RuntimeException failure) {
            throw invalidCursorOrPage("cursor is invalid");
        }
    }

    private String encode(JsonNode cursor) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(cursor));
    }

    private JsonNode decode(String cursor) {
        return objectMapper.readTree(Base64.getUrlDecoder().decode(cursor));
    }

    private ApiProblemException invalidCursorOrPage(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "invalid_pagination", detail);
    }

    private String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return null;
        }
        var normalized = severity.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SEVERITIES.contains(normalized)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_severity",
                    "severity must be one of sev1, sev2, sev3, sev4");
        }
        return normalized;
    }

    private record IncidentCursor(Instant openedAt, UUID id) {}
}
