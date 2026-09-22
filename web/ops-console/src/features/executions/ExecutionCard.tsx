import { useAuth } from "../../auth/authContext";
import type {
    CockpitApproval,
    CockpitExecution,
} from "../incidents/incidentApi";
import { staleMessage, useCreateExecution } from "../incidents/incidentApi";

export function ExecutionCard({
    incidentId,
    incidentVersion,
    proposalId,
    approval,
    execution,
}: {
    incidentId: string;
    incidentVersion: number;
    proposalId: string;
    approval: CockpitApproval | null;
    execution: CockpitExecution | null;
}) {
    const { user } = useAuth();
    const mutation = useCreateExecution(incidentId);
    const operator = Boolean(
        user?.roles.some(
            (role) => role === "ON_CALL_OPERATOR" || role === "PLATFORM_ADMIN",
        ),
    );
    const approved = approval?.status === "APPROVED";
    const error = staleMessage(mutation.error) ?? mutation.error?.message;

    return (
        <section
            className="control-card execution-card"
            aria-labelledby="execution-title"
        >
            <header className="card-heading compact-heading">
                <div>
                    <p className="eyebrow">CONTROLLED EXECUTION</p>
                    <h2 id="execution-title">安全执行</h2>
                </div>
                <span
                    className={`state-chip state-${(execution?.status ?? "idle").toLowerCase()}`}
                >
                    {execution?.status ?? "IDLE"}
                </span>
            </header>

            {execution ? (
                <dl className="execution-metadata">
                    <div>
                        <dt>执行 ID</dt>
                        <dd className="mono-value">
                            {execution.id.slice(0, 12)}…
                        </dd>
                    </div>
                    <div>
                        <dt>目标</dt>
                        <dd>{execution.targetAlias}</dd>
                    </div>
                    <div>
                        <dt>Fencing token</dt>
                        <dd>{execution.fencingToken}</dd>
                    </div>
                </dl>
            ) : (
                <p className="policy-note">
                    只有服务器确认审批为 APPROVED
                    后，值班工程师才能显式启动执行。
                </p>
            )}

            {!execution && approved ? (
                <button
                    className="primary-action full-action"
                    type="button"
                    disabled={!operator || mutation.isPending}
                    onClick={() =>
                        mutation.mutate({
                            proposalId,
                            version: incidentVersion,
                        })
                    }
                >
                    执行已审批方案
                </button>
            ) : null}
            <p className="mutation-status" role="status" aria-live="polite">
                {mutation.isPending ? "执行请求已发送，等待服务器确认…" : error}
            </p>
        </section>
    );
}
