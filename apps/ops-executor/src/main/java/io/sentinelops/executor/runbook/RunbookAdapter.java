package io.sentinelops.executor.runbook;

import java.util.Set;

public interface RunbookAdapter {

    String adapterId();

    Set<String> supportedOperations();

    ExecutionStepResult execute(AuthorizedRunbookStep step, IdempotencyContext context);
}
