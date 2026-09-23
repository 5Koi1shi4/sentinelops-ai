package io.sentinelops.api.audit.application;

import io.sentinelops.api.audit.adapter.out.persistence.AuditStore;
import io.sentinelops.api.audit.domain.AuditRecord;
import io.sentinelops.api.identity.application.AuthorizationService;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.shared.audit.AuditCommand;
import io.sentinelops.api.shared.audit.AuditRecorder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditService implements AuditRecorder {
    private static final Set<String> NUMERIC_FIELDS = Set.of("revision", "versionNumber", "attempt");
    private static final Set<String> BOOLEAN_FIELDS = Set.of("releaseAllowed");
    private static final Map<String, Set<String>> ENUM_FIELDS = Map.of(
            "status", Set.of("pending", "approved", "rejected", "expired", "invalidated",
                    "draft", "published", "retired", "running", "completed", "failed",
                    "executing", "verifying", "resolved"),
            "decision", Set.of("approve", "reject"),
            "riskLevel", Set.of("r0", "r1", "r2"),
            "failureCode", Set.of("EVAL_RUN_FAILED", "EVAL_INTERRUPTED"),
            "reasonCode", Set.of("expired", "incident_state_changed",
                    "proposal_hash_changed", "execution_target_changed"));

    private final AuditStore store;
    private final UuidV7Generator ids;
    private final ObjectMapper json;

    public AuditService(AuditStore store, UuidV7Generator ids, ObjectMapper json) {
        this.store = store;
        this.ids = ids;
        this.json = json;
    }

    @Transactional
    @Override
    public UUID record(AuditCommand command) {
        Objects.requireNonNull(command, "command");
        UUID id = ids.generate();
        store.append(new AuditRecord(id, command.serviceId(),
                token(command.actorType(), 16), safeText(command.actorId(), 256),
                token(command.action(), 100), token(command.resourceType(), 80),
                safeText(command.resourceId(), 256), result(command.result()),
                optionalToken(command.beforeHash(), 128), optionalToken(command.afterHash(), 128),
                json.valueToTree(sanitize(command.metadata())),
                optionalToken(command.traceId() == null ? MDC.get("traceId") : command.traceId(), 128),
                Instant.now()));
        return id;
    }

    @Transactional(readOnly = true)
    public AuditPage list(CurrentPrincipal principal, UUID serviceId, String before, int limit) {
        AuthorizationService.require(principal, AuthorizationService.Action.READ_AUDIT, serviceId);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("invalid audit page size");
        Cursor cursor = decode(before);
        List<AuditRecord> rows = store.list(serviceId,
                cursor == null ? null : cursor.time(), cursor == null ? null : cursor.id(), limit + 1);
        boolean hasNext = rows.size() > limit;
        List<AuditRecord> items = hasNext ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        return new AuditPage(items, hasNext ? encode(items.getLast()) : null);
    }

    private static Map<String, Object> sanitize(Map<String, ?> input) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (input == null) return safe;
        for (var entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null) continue;
            if (NUMERIC_FIELDS.contains(key) && (value instanceof Integer || value instanceof Long)
                    && value instanceof Number number
                    && number.longValue() >= 0 && number.longValue() <= 1_000_000) {
                safe.put(key, number.longValue());
            } else if (BOOLEAN_FIELDS.contains(key) && value instanceof Boolean) {
                safe.put(key, value);
            } else if (ENUM_FIELDS.containsKey(key) && value instanceof String text
                    && ENUM_FIELDS.get(key).contains(text)) {
                safe.put(key, text);
            }
        }
        return safe;
    }

    private static String result(String value) {
        if (!Set.of("success", "failure", "denied").contains(value))
            throw new IllegalArgumentException("invalid audit result");
        return value;
    }

    private static String token(String value, int max) {
        if (value == null || value.length() > max || !value.matches("[A-Za-z][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("invalid audit identifier");
        return value;
    }

    private static String optionalToken(String value, int max) {
        if (value == null) return null;
        if (value.length() > max || !value.matches("[A-Za-z0-9_.:-]+"))
            throw new IllegalArgumentException("invalid audit reference");
        return value;
    }

    private static String safeText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid audit subject or resource");
        return value;
    }

    private static String encode(AuditRecord last) {
        String value = last.occurredAt() + "|" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String value) {
        if (value == null) return null;
        if (value.length() > 160) throw new IllegalArgumentException("invalid audit cursor");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException("invalid audit cursor");
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid audit cursor");
        }
    }

    public record AuditPage(List<AuditRecord> items, String nextCursor) {}
    private record Cursor(Instant time, UUID id) {}
}
