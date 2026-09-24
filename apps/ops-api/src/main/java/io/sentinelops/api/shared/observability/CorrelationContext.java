package io.sentinelops.api.shared.observability;

import io.micrometer.observation.Observation;
import io.opentelemetry.api.logs.LogRecordBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;

/** UUID 只进入 Span 属性和当前线程的日志上下文，不进入指标标签。 */
public final class CorrelationContext implements AutoCloseable {
    private static final Set<String> KEYS = Set.of(
            "incident.id", "diagnosis.run.id", "approval.id", "execution.id");

    private final Observation observation;
    private final Map<String, String> previous = new HashMap<>();
    private final Map<String, String> current = new HashMap<>();

    CorrelationContext(Observation observation) {
        this.observation = Objects.requireNonNull(observation, "observation");
    }

    public CorrelationContext id(String key, UUID value) {
        if (!KEYS.contains(key)) {
            throw new IllegalArgumentException("Unsupported correlation key");
        }
        if (value == null) {
            return this;
        }
        String text = value.toString();
        observation.highCardinalityKeyValue(key, text);
        current.put(key, text);
        if (!previous.containsKey(key)) {
            previous.put(key, MDC.get(key));
        }
        MDC.put(key, text);
        return this;
    }

    void addTo(LogRecordBuilder record) {
        current.forEach(record::setAttribute);
    }

    @Override
    public void close() {
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }
}
