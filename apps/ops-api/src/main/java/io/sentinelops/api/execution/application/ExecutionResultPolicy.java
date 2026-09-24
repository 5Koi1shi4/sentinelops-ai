package io.sentinelops.api.execution.application;

import org.springframework.stereotype.Component;

/** 只有经目标契约证明稳定幂等键和 fencing 的操作才允许在分发后重放。 */
@Component
public final class ExecutionResultPolicy {

    public boolean mayReplayAfterDispatch(
            String adapterId, String operation, String targetAlias) {
        return "demo-http".equals(adapterId)
                && "recover_connection_pool".equals(operation)
                && "demo-checkout".equals(targetAlias);
    }
}
