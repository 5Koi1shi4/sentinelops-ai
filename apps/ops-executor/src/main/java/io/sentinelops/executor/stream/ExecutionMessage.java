package io.sentinelops.executor.stream;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.redis.connection.stream.MapRecord;

public record ExecutionMessage(String recordId, UUID eventId, UUID executionId) {

    public ExecutionMessage {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank");
        }
        recordId = recordId.trim();
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(executionId, "executionId");
    }

    public static ExecutionMessage from(MapRecord<String, Object, Object> record) {
        Map<Object, Object> values = record.getValue();
        return new ExecutionMessage(
                record.getId().getValue(),
                UUID.fromString(required(values, "eventId")),
                UUID.fromString(required(values, "executionId")));
    }

    private static String required(Map<Object, Object> values, String field) {
        var value = values.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Execution message is missing " + field);
        }
        return value.toString();
    }
}
