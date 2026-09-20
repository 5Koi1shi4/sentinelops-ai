package io.sentinelops.executor.stream;

import io.sentinelops.executor.controlplane.ControlPlaneClient.ClaimedExecution;

public interface ExecutionLeaseHeartbeat {

    ActiveLease start(ExecutionMessage message, ClaimedExecution claim, String attemptId);

    interface ActiveLease extends AutoCloseable {

        String ticketForResult();

        @Override
        void close();
    }
}
