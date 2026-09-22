import { Link, useParams } from "react-router-dom";

import { RequireRole } from "../../auth/RequireRole";
import { ApprovalCard } from "../approvals/ApprovalCard";
import { ExecutionCard } from "../executions/ExecutionCard";
import { DiagnosisPanel } from "./DiagnosisPanel";
import { IncidentTimeline } from "./IncidentTimeline";
import {
    staleMessage,
    useIncident,
    useIncidentTimeline,
    useRequestApproval,
    useStartDiagnosis,
} from "./incidentApi";

const responseStages = [
    ["DETECTED", "信号接入"],
    ["TRIAGING", "事故归并"],
    ["DIAGNOSED", "证据诊断"],
    ["AWAITING_APPROVAL", "人工审批"],
    ["EXECUTING", "安全执行"],
    ["VERIFYING", "恢复验证"],
    ["RESOLVED", "事故关闭"],
] as const;

export function IncidentDetailPage({
    incidentId: suppliedId,
}: {
    incidentId?: string;
}) {
    const parameters = useParams<{ incidentId: string }>();
    const incidentId = suppliedId ?? parameters.incidentId ?? "";
    const cockpitQuery = useIncident(incidentId);
    const timelineQuery = useIncidentTimeline(incidentId);
    const diagnose = useStartDiagnosis(incidentId);
    const requestApproval = useRequestApproval(incidentId);

    if (!incidentId) {
        return <p role="alert">事故 ID 缺失。</p>;
    }
    if (cockpitQuery.isPending) {
        return (
            <div className="detail-loading" role="status">
                正在装载事故驾驶舱…
            </div>
        );
    }
    if (cockpitQuery.isError) {
        return (
            <div className="detail-loading" role="alert">
                无法读取事故：{cockpitQuery.error.message}
            </div>
        );
    }

    const cockpit = cockpitQuery.data;
    const { incident, diagnosis, activeApproval, latestExecution } = cockpit;
    const currentStage = responseStages.findIndex(
        ([status]) => status === incident.status,
    );
    const actionError =
        staleMessage(diagnose.error) ??
        staleMessage(requestApproval.error) ??
        diagnose.error?.message ??
        requestApproval.error?.message;

    return (
        <div className="incident-detail">
            <nav className="breadcrumb" aria-label="面包屑">
                <Link to="/incidents">事故中心</Link>
                <span aria-hidden="true">/</span>
                <span>{incident.serviceKey}</span>
            </nav>

            <header className="incident-command-header">
                <div>
                    <p className="eyebrow">
                        INCIDENT / {incident.id.slice(0, 12)}
                    </p>
                    <h1>{incident.title}</h1>
                    <p className="incident-subline">
                        {incident.serviceKey} · 发生 {incident.occurrenceCount}{" "}
                        次 · 资源版本 v{incident.version}
                    </p>
                </div>
                <div className="incident-state-lockup">
                    <span className={`severity-mark ${incident.severity}`}>
                        {incident.severity.toUpperCase()}
                    </span>
                    <strong>{incident.status}</strong>
                </div>
            </header>

            <ol className="response-spine" aria-label="事故响应进度">
                {responseStages.map(([status, label], index) => (
                    <li
                        key={status}
                        className={
                            index < currentStage
                                ? "is-complete"
                                : index === currentStage
                                  ? "is-current"
                                  : undefined
                        }
                    >
                        <span className="spine-node" aria-hidden="true" />
                        <small>{label}</small>
                    </li>
                ))}
            </ol>

            <div className="cockpit-grid">
                <div className="cockpit-primary">
                    {diagnosis ? (
                        <DiagnosisPanel
                            diagnosis={diagnosis}
                            evidence={cockpit.evidence}
                        />
                    ) : (
                        <section className="control-card empty-diagnosis">
                            <p className="eyebrow">DIAGNOSIS</p>
                            <h2>尚未形成诊断建议</h2>
                            <p>
                                运行诊断后，证据引用、建议和验证条件会显示在这里。
                            </p>
                            <RequireRole anyOf={["ON_CALL_OPERATOR"]}>
                                <button
                                    className="primary-action"
                                    type="button"
                                    disabled={diagnose.isPending}
                                    onClick={() =>
                                        diagnose.mutate({
                                            version: incident.version,
                                        })
                                    }
                                >
                                    运行证据诊断
                                </button>
                            </RequireRole>
                        </section>
                    )}

                    {diagnosis &&
                    incident.status === "DIAGNOSED" &&
                    !activeApproval ? (
                        <section className="control-card submit-approval-card">
                            <div>
                                <p className="eyebrow">EXPLICIT HANDOFF</p>
                                <h2>将建议提交人工审批</h2>
                                <p>
                                    提交会冻结提案哈希、目标、Runbook
                                    版本和验证条件。
                                </p>
                            </div>
                            <RequireRole anyOf={["ON_CALL_OPERATOR"]}>
                                <button
                                    className="primary-action"
                                    type="button"
                                    disabled={requestApproval.isPending}
                                    onClick={() =>
                                        requestApproval.mutate({
                                            proposalId: diagnosis.id,
                                            version: incident.version,
                                        })
                                    }
                                >
                                    提交审批
                                </button>
                            </RequireRole>
                        </section>
                    ) : null}

                    {timelineQuery.data ? (
                        <IncidentTimeline
                            timeline={timelineQuery.data}
                            evidence={cockpit.evidence}
                        />
                    ) : null}
                </div>

                <aside className="cockpit-actions" aria-label="事故操作">
                    {diagnosis && activeApproval ? (
                        <ApprovalCard
                            incidentId={incidentId}
                            incidentVersion={incident.version}
                            diagnosis={diagnosis}
                            approval={activeApproval}
                        />
                    ) : null}
                    {diagnosis ? (
                        <ExecutionCard
                            incidentId={incidentId}
                            incidentVersion={incident.version}
                            proposalId={diagnosis.id}
                            approval={activeApproval}
                            execution={latestExecution}
                        />
                    ) : null}
                </aside>
            </div>
            <p
                className="mutation-status global-status"
                role="status"
                aria-live="polite"
            >
                {diagnose.isPending
                    ? "正在分析证据…"
                    : requestApproval.isPending
                      ? "正在提交审批…"
                      : actionError}
            </p>
        </div>
    );
}
