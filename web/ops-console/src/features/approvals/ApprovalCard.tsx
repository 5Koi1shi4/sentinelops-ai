import { useState } from "react";

import { useAuth } from "../../auth/authContext";
import type {
    CockpitApproval,
    CockpitDiagnosis,
} from "../incidents/incidentApi";
import { staleMessage, useDecideApproval } from "../incidents/incidentApi";

function minutesUntil(expiresAt: string): number {
    return Math.max(
        0,
        Math.ceil((Date.parse(expiresAt) - Date.now()) / 60_000),
    );
}

export function ApprovalCard({
    incidentId,
    incidentVersion,
    diagnosis,
    approval,
}: {
    incidentId: string;
    incidentVersion: number;
    diagnosis: CockpitDiagnosis;
    approval: CockpitApproval;
}) {
    const { user } = useAuth();
    const [rejectionReason, setRejectionReason] = useState("");
    const mutation = useDecideApproval(incidentId, approval.id);
    const selfApproval = Boolean(
        approval.independentApproverRequired &&
        user?.subject === approval.requesterSubject,
    );
    const hasApproverRole = Boolean(
        user?.roles.some(
            (role) => role === "SRE_APPROVER" || role === "PLATFORM_ADMIN",
        ),
    );
    const pending = approval.status === "PENDING";
    const canDecide = pending && hasApproverRole && !selfApproval;
    const minutes = minutesUntil(approval.expiresAt);
    const verification = diagnosis.expectedVerification;
    const error = staleMessage(mutation.error) ?? mutation.error?.message;

    const decide = (decision: "approve" | "reject", comment: string) => {
        mutation.mutate({
            decision,
            comment,
            proposalHash: approval.proposalHash,
            version: incidentVersion,
        });
    };

    return (
        <section
            className="control-card approval-card"
            aria-labelledby="approval-title"
        >
            <header className="card-heading compact-heading">
                <div>
                    <p className="eyebrow">HUMAN GATE / SEPARATION OF DUTIES</p>
                    <h2 id="approval-title">审批检查</h2>
                </div>
                <span
                    className={`state-chip state-${approval.status.toLowerCase()}`}
                >
                    {approval.status}
                </span>
            </header>

            <dl className="approval-grid">
                <div>
                    <dt>提案</dt>
                    <dd className="mono-value">
                        {approval.proposalHash.slice(0, 12)}…
                    </dd>
                </div>
                <div>
                    <dt>Runbook</dt>
                    <dd>
                        {diagnosis.runbookKey} · v{diagnosis.runbookVersion}
                    </dd>
                </div>
                <div>
                    <dt>目标</dt>
                    <dd>{approval.targetAlias}</dd>
                </div>
                <div>
                    <dt>参数</dt>
                    <dd>{JSON.stringify(diagnosis.parameters)}</dd>
                </div>
                <div>
                    <dt>风险</dt>
                    <dd>{diagnosis.riskLevel}</dd>
                </div>
                <div>
                    <dt>失效</dt>
                    <dd>{minutes} 分钟后</dd>
                </div>
                <div>
                    <dt>验证</dt>
                    <dd>
                        {verification
                            ? `${verification.probe} · ${verification.attempts} 次`
                            : "未配置"}
                    </dd>
                </div>
                <div>
                    <dt>请求者</dt>
                    <dd>{approval.requesterDisplayName}</dd>
                </div>
            </dl>

            {pending ? (
                <div className="decision-zone">
                    {selfApproval ? (
                        <p className="policy-warning">
                            请求者不能审批自己的 R1 变更
                        </p>
                    ) : null}
                    {!hasApproverRole ? (
                        <p className="policy-note">
                            需要 SRE 审批人确认此变更。
                        </p>
                    ) : null}
                    <label htmlFor={`rejection-reason-${approval.id}`}>
                        驳回原因
                    </label>
                    <textarea
                        id={`rejection-reason-${approval.id}`}
                        value={rejectionReason}
                        onChange={(event) =>
                            setRejectionReason(event.target.value)
                        }
                        placeholder="驳回时必须说明风险或缺失信息"
                        rows={3}
                        maxLength={2000}
                    />
                    <div className="action-row">
                        <button
                            className="primary-action"
                            type="button"
                            disabled={
                                !canDecide ||
                                mutation.isPending ||
                                minutes === 0
                            }
                            onClick={() =>
                                decide("approve", "已核验范围与验证条件")
                            }
                        >
                            批准 {minutes} 分钟
                        </button>
                        <button
                            className="secondary-action danger-action"
                            type="button"
                            disabled={
                                !canDecide ||
                                mutation.isPending ||
                                !rejectionReason.trim()
                            }
                            onClick={() =>
                                decide("reject", rejectionReason.trim())
                            }
                        >
                            驳回方案
                        </button>
                    </div>
                </div>
            ) : (
                <p className="confirmed-state">服务器确认：{approval.status}</p>
            )}
            <p className="mutation-status" role="status" aria-live="polite">
                {mutation.isPending ? "正在记录审批决定…" : error}
            </p>
        </section>
    );
}
