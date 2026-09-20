package io.sentinelops.executor.stream;

public interface ExecutionMessageAcknowledger {

    void acknowledge(ExecutionMessage message);
}
