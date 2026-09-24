package io.sentinelops.executor.adapter.kubernetes;

import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.UnsupportedRunbookStepException;
import java.util.Objects;
import java.util.Set;

public final class KubernetesActionPolicy {
    private KubernetesActionPolicy() {}

    public static KubernetesTargetCatalog.Target authorize(
            AuthorizedRunbookStep step, IdempotencyContext context, KubernetesTargetCatalog catalog) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(catalog, "catalog");
        var target = catalog.targets().get(step.target());
        if (!"kubernetes".equals(step.adapterId()) || target == null
                || !target.operations().contains(step.operation())
                || !step.executionId().equals(context.executionId())
                || !step.stepId().equals(context.stepId())
                || step.fencingToken() != context.fencingToken()) {
            throw new UnsupportedRunbookStepException("Kubernetes Runbook step is not cataloged");
        }
        if ("restart_deployment".equals(step.operation())) {
            if (!step.parameters().isEmpty()) {
                throw new UnsupportedRunbookStepException("Restart accepts no model parameters");
            }
        } else if ("scale_deployment".equals(step.operation())) {
            Object replicas = step.parameters().get("replicas");
            if (!step.parameters().keySet().equals(Set.of("replicas"))
                    || !(replicas instanceof Integer count)
                    || count < target.minReplicas() || count > target.maxReplicas()) {
                throw new UnsupportedRunbookStepException("Replica count is outside catalog bounds");
            }
        } else {
            throw new UnsupportedRunbookStepException("Kubernetes operation is not supported");
        }
        return target;
    }
}
