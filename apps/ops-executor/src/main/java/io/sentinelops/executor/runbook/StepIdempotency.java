package io.sentinelops.executor.runbook;

import java.util.Objects;

/** 重放要求目标契约同时保证稳定幂等键和 fencing。 */
public final class StepIdempotency {

    private StepIdempotency() {}

    public static boolean mayReplayAfterDispatch(AuthorizedRunbookStep step) {
        Objects.requireNonNull(step, "step");
        return "demo-http".equals(step.adapterId())
                && "recover_connection_pool".equals(step.operation())
                && "demo-checkout".equals(step.target());
    }
}
