package io.sentinelops.executor.ticket;

public final class InvalidExecutionTicket extends RuntimeException {

    public InvalidExecutionTicket(String message) {
        super(message);
    }
}
