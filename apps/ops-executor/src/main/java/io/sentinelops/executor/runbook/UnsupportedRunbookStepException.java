package io.sentinelops.executor.runbook;

public final class UnsupportedRunbookStepException extends RuntimeException {

    public UnsupportedRunbookStepException(String message) {
        super(message);
    }
}
