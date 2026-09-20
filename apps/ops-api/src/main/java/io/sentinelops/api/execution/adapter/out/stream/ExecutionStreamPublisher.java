package io.sentinelops.api.execution.adapter.out.stream;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ExecutionStreamPublisher {

    private final StringRedisTemplate redis;
    private final String stream;

    public ExecutionStreamPublisher(
            StringRedisTemplate redis,
            @Value("${sentinelops.outbox.stream:sentinelops.executions}") String stream) {
        this.redis = Objects.requireNonNull(redis, "redis");
        if (stream == null || stream.isBlank()) {
            throw new IllegalArgumentException("stream must not be blank");
        }
        this.stream = stream;
    }

    public void publish(UUID eventId, UUID executionId) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(executionId, "executionId");
        var record = StreamRecords.<String, String, String>mapBacked(Map.of(
                        "eventId", eventId.toString(),
                        "executionId", executionId.toString()))
                .withStreamKey(stream);
        if (redis.opsForStream().add(record) == null) {
            throw new IllegalStateException("Valkey did not return a Stream record ID");
        }
    }
}
