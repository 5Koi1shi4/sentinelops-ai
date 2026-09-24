package io.sentinelops.executor.adapter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.RunbookAdapter;
import io.sentinelops.executor.runbook.UnsupportedRunbookStepException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public interface AdapterContractTest {

    RunbookAdapter adapter();

    String allowedOperation();

    String allowedTarget();

    Map<String, Object> allowedParameters();

    @Test
    default void rejectsUnknownOperationAndTarget() {
        assertRejected("delete", allowedTarget(), allowedParameters());
        assertRejected(allowedOperation(), "other-target", allowedParameters());
    }

    @Test
    default void rejectsExtraParameters() {
        var parameters = new java.util.LinkedHashMap<>(allowedParameters());
        parameters.put("namespace", "attacker-controlled");
        assertRejected(allowedOperation(), allowedTarget(), parameters);
    }

    private void assertRejected(String operation, String target, Map<String, Object> parameters) {
        UUID executionId = UUID.randomUUID();
        var context = new IdempotencyContext(executionId, "step-one", 19);
        var step = new AuthorizedRunbookStep(
                executionId, UUID.randomUUID(), UUID.randomUUID(), "checksum", "step-one",
                operation, adapter().adapterId(), parameters, target, "R1", 19);
        assertThatThrownBy(() -> adapter().execute(step, context, () -> {
            throw new AssertionError("Rejected step reached transport");
        })).isInstanceOf(UnsupportedRunbookStepException.class);
    }
}
