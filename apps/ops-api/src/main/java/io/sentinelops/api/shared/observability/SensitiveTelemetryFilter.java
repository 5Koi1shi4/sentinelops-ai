package io.sentinelops.api.shared.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 对平台自定义遥测施加可验证的标签白名单。 */
@Component
public final class SensitiveTelemetryFilter implements ObservationFilter, MeterFilter {
    private static final Pattern REGISTERED_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_./:-]{0,63}");
    private static final Set<String> UUID_KEYS = Set.of(
            "incident.id", "diagnosis.run.id", "approval.id", "execution.id");
    private static final Map<String, Set<String>> FINITE_VALUES = Map.ofEntries(
            Map.entry("result", Set.of("success", "failure", "error", "rejected", "unknown",
                    "approved", "denied", "expired", "rate_limited", "budget_exceeded",
                    "invalid", "not_found", "conflict", "skipped")),
            Map.entry("risk", Set.of("r0", "r1", "r2", "r3", "unknown")),
            Map.entry("severity", Set.of("sev1", "sev2", "sev3", "sev4")),
            Map.entry("source", Set.of("alertmanager", "prometheus", "loki", "manual",
                    "fixture", "webhook")),
            Map.entry("provider", Set.of("openai-compatible", "ollama", "deterministic")),
            Map.entry("status", Set.of("detected", "triaging", "diagnosed",
                    "awaiting_approval", "approved", "rejected", "executing", "verifying",
                    "resolved", "escalated", "pending", "succeeded", "failed", "unknown",
                    "invalidated")),
            Map.entry("tool", Set.of("queryMetrics", "queryLogs", "getEvidence",
                    "searchRunbooks")),
            Map.entry("type", Set.of("prompt", "completion")),
            Map.entry("error", Set.of("none", "error")));
    private static final Set<String> REGISTERED_KEYS = Set.of("query", "model", "adapter");

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!platformName(context.getName())) {
            return context;
        }
        for (KeyValue entry : context.getLowCardinalityKeyValues()) {
            context.removeLowCardinalityKeyValue(entry.getKey());
            String safe = lowCardinality(entry.getKey(), entry.getValue());
            if (safe != null) {
                context.addLowCardinalityKeyValue(KeyValue.of(entry.getKey(), safe));
            }
        }
        for (KeyValue entry : context.getHighCardinalityKeyValues()) {
            context.removeHighCardinalityKeyValue(entry.getKey());
            if (UUID_KEYS.contains(entry.getKey()) && canonicalUuid(entry.getValue())) {
                context.addHighCardinalityKeyValue(entry);
            }
        }
        return context;
    }

    @Override
    public Meter.Id map(Meter.Id id) {
        if (!platformName(id.getName())) {
            return id;
        }
        var safe = new ArrayList<Tag>();
        for (Tag tag : id.getTags()) {
            String value = lowCardinality(tag.getKey(), tag.getValue());
            if (value != null) {
                safe.add(Tag.of(tag.getKey(), value));
            }
        }
        return id.replaceTags(safe);
    }

    private static boolean platformName(String name) {
        return name != null && name.startsWith("sentinelops.");
    }

    private static String lowCardinality(String key, String value) {
        if (key == null || value == null) {
            return null;
        }
        Set<String> allowed = FINITE_VALUES.get(key);
        if (allowed != null) {
            return allowed.contains(value) ? value : "unknown";
        }
        return REGISTERED_KEYS.contains(key) && REGISTERED_NAME.matcher(value).matches()
                ? value : null;
    }

    private static boolean canonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
