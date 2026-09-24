package io.sentinelops.executor.stream;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "sentinelops.executor.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RedisExecutionStreamConsumer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisExecutionStreamConsumer.class);

    private final StringRedisTemplate redis;
    private final ExecutionMessageListener listener;
    private final String stream;
    private final String group;
    private final String consumerId;
    private final Duration reclaimIdle;
    private final int batchSize;
    private final AtomicBoolean groupReady = new AtomicBoolean();
    private final AtomicReference<Double> streamLag = new AtomicReference<>(Double.NaN);

    public RedisExecutionStreamConsumer(
            StringRedisTemplate redis,
            ExecutionMessageListener listener,
            @Value("${sentinelops.executor.stream}") String stream,
            @Value("${sentinelops.executor.consumer-group}") String group,
            @Value("${sentinelops.executor.id:}") String consumerId,
            @Value("${sentinelops.executor.reclaim-idle:PT35S}") Duration reclaimIdle,
            @Value("${sentinelops.executor.batch-size:10}") int batchSize,
            MeterRegistry meters) {
        this.redis = java.util.Objects.requireNonNull(redis, "redis");
        this.listener = java.util.Objects.requireNonNull(listener, "listener");
        this.stream = requireText(stream, "stream");
        this.group = requireText(group, "group");
        this.consumerId = consumerId == null || consumerId.isBlank()
                ? "executor-" + UUID.randomUUID()
                : consumerId.trim();
        this.reclaimIdle = java.util.Objects.requireNonNull(reclaimIdle, "reclaimIdle");
        if (reclaimIdle.isNegative() || reclaimIdle.isZero()) {
            throw new IllegalArgumentException("reclaimIdle must be positive");
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        this.batchSize = batchSize;
        Gauge.builder("sentinelops.executor.stream.lag", streamLag, AtomicReference::get)
                .description("Undelivered Stream records for the executor consumer group")
                .register(java.util.Objects.requireNonNull(meters, "meters"));
    }

    @PostConstruct
    void initializeGroup() {
        tryEnsureGroup();
    }

    @Scheduled(fixedDelayString = "${sentinelops.executor.poll-interval:PT0.5S}")
    public int pollOnce() {
        if (!groupReady.get() && !tryEnsureGroup()) {
            return 0;
        }
        try {
            var records = new ArrayList<MapRecord<String, Object, Object>>();
            records.addAll(reclaimPending());
            var fresh = readFresh();
            if (fresh != null) {
                records.addAll(fresh);
            }
            records.forEach(this::process);
            refreshLag();
            return records.size();
        } catch (DataAccessException unavailable) {
            groupReady.set(false);
            streamLag.set(Double.NaN);
            LOGGER.warn("Execution Stream polling failed errorType={}",
                    unavailable.getClass().getSimpleName());
            return 0;
        }
    }

    private void refreshLag() {
        try {
            streamLag.set(redis.opsForStream().groups(stream).stream()
                    .filter(info -> group.equals(info.groupName()))
                    .map(info -> info.getRaw().get("lag"))
                    .filter(Number.class::isInstance)
                    .map(Number.class::cast)
                    .map(Number::doubleValue)
                    .findFirst().orElse(Double.NaN));
        } catch (DataAccessException unavailable) {
            streamLag.set(Double.NaN);
        }
    }

    @SuppressWarnings("unchecked")
    private List<MapRecord<String, Object, Object>> readFresh() {
        return redis.<Object, Object>opsForStream().read(
                Consumer.from(group, consumerId),
                StreamReadOptions.empty().count(batchSize),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
    }

    private List<MapRecord<String, Object, Object>> reclaimPending() {
        var operations = redis.<Object, Object>opsForStream();
        var pending = operations.pending(
                stream, group, Range.unbounded(), batchSize, reclaimIdle);
        if (pending.isEmpty()) {
            return List.of();
        }
        RecordId[] ids = pending.stream()
                .map(message -> message.getId())
                .toArray(RecordId[]::new);
        var claimed = operations.claim(stream, group, consumerId, reclaimIdle, ids);
        return claimed == null ? List.of() : claimed;
    }

    private void process(MapRecord<String, Object, Object> record) {
        try {
            listener.onMessage(ExecutionMessage.from(record));
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Execution Stream message remains pending recordId={} errorType={}",
                    record.getId().getValue(),
                    failure.getClass().getSimpleName());
        }
    }

    private boolean tryEnsureGroup() {
        try {
            redis.execute((RedisCallback<Void>) connection -> {
                connection.streamCommands()
                        .xGroupCreate(
                                stream.getBytes(StandardCharsets.UTF_8),
                                group,
                                ReadOffset.from("0-0"),
                                true);
                return null;
            });
            groupReady.set(true);
            return true;
        } catch (DataAccessException failure) {
            if (containsBusyGroup(failure)) {
                groupReady.set(true);
                return true;
            }
            LOGGER.warn("Execution Stream consumer group is unavailable errorType={}",
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    private boolean containsBusyGroup(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
