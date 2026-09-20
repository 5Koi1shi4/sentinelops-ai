package io.sentinelops.executor.stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisExecutionMessageAcknowledger implements ExecutionMessageAcknowledger {

    private final StringRedisTemplate redis;
    private final String stream;
    private final String group;

    public RedisExecutionMessageAcknowledger(
            StringRedisTemplate redis,
            @Value("${sentinelops.executor.stream}") String stream,
            @Value("${sentinelops.executor.consumer-group}") String group) {
        this.redis = java.util.Objects.requireNonNull(redis, "redis");
        this.stream = requireText(stream, "stream");
        this.group = requireText(group, "group");
    }

    @Override
    public void acknowledge(ExecutionMessage message) {
        var acknowledged = redis.opsForStream()
                .acknowledge(stream, group, message.recordId());
        if (acknowledged == null) {
            throw new IllegalStateException("Valkey did not acknowledge the Stream message");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
