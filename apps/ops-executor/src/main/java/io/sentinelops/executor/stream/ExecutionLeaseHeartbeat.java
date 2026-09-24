package io.sentinelops.executor.stream;

import io.sentinelops.executor.controlplane.ControlPlaneClient.ClaimedExecution;
import java.util.function.Consumer;

public interface ExecutionLeaseHeartbeat {

    ActiveLease start(ExecutionMessage message, ClaimedExecution claim, String attemptId);

    interface ActiveLease extends AutoCloseable {

        void withCurrentTicket(Consumer<String> action);

        String ticketForResult();

        @Override
        void close();
    }
}
