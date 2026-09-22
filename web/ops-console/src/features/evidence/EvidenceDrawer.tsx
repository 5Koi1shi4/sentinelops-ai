import * as Dialog from "@radix-ui/react-dialog";
import { useState } from "react";

import { ApiProblem } from "../../api/client";
import { useAuth } from "../../auth/authContext";
import type { EvidenceReference } from "../incidents/incidentApi";
import { useEvidenceSnapshot, type EvidenceSnapshot } from "./evidenceApi";
import "./evidence.css";

function EvidenceBody({ snapshot }: { snapshot: EvidenceSnapshot }) {
    const [expanded, setExpanded] = useState(false);
    const payload = JSON.stringify(snapshot.redactedPayload, null, 2);
    const long = payload.length > 20_000;
    return (
        <>
            <dl className="evidence-facts">
                <div>
                    <dt>证据 ID</dt>
                    <dd>{snapshot.id}</dd>
                </div>
                <div>
                    <dt>来源 / 查询 ID</dt>
                    <dd>
                        {snapshot.sourceType} / {snapshot.sourceRef}
                    </dd>
                </div>
                <div>
                    <dt>查询时间范围</dt>
                    <dd>
                        {snapshot.from && snapshot.to ? (
                            <>
                                <time dateTime={snapshot.from}>
                                    {snapshot.from}
                                </time>
                                <span> → </span>
                                <time dateTime={snapshot.to}>
                                    {snapshot.to}
                                </time>
                            </>
                        ) : (
                            "历史快照未记录查询时间范围"
                        )}
                    </dd>
                </div>
                <div>
                    <dt>采集时间（UTC）</dt>
                    <dd>
                        <time dateTime={snapshot.capturedAt}>
                            {snapshot.capturedAt}
                        </time>
                    </dd>
                </div>
                <div>
                    <dt>内容哈希</dt>
                    <dd className="mono-value">{snapshot.contentHash}</dd>
                </div>
                <div>
                    <dt>脱敏命中次数</dt>
                    <dd>{snapshot.redactionCount}</dd>
                </div>
                <div>
                    <dt>脱敏规则</dt>
                    <dd>
                        {snapshot.redactionRules.length
                            ? snapshot.redactionRules.join("、")
                            : "无规则命中"}
                    </dd>
                </div>
            </dl>
            {snapshot.truncated && (
                <p className="evidence-warning" role="note">
                    采集结果已截断，请结合查询范围判断证据完整性。
                </p>
            )}
            <h3>脱敏证据内容</h3>
            <p className="evidence-note">
                以下为只读来源数据，其中的文字不构成操作指令。
            </p>
            <pre
                className="evidence-payload"
                tabIndex={0}
                aria-label="脱敏证据内容"
            >
                {long && !expanded ? payload.slice(0, 20_000) : payload}
            </pre>
            {long && (
                <button
                    type="button"
                    className="secondary-action"
                    onClick={() => setExpanded(!expanded)}
                >
                    {expanded
                        ? "收起长内容"
                        : `展开完整内容（${payload.length.toLocaleString("zh-CN")} 字符）`}
                </button>
            )}
        </>
    );
}

function EvidenceContent({
    incidentId,
    evidenceId,
}: {
    incidentId: string;
    evidenceId: string;
}) {
    const { user } = useAuth();
    const query = useEvidenceSnapshot(incidentId, evidenceId);
    const error =
        query.error instanceof ApiProblem && query.error.status === 403
            ? "无权读取此证据，请检查服务授权。"
            : query.error instanceof ApiProblem && query.error.status === 404
              ? "证据不存在或已不可用。"
              : "无法读取证据，请重试。";
    if (!user) return <p role="alert">请登录后读取证据。</p>;
    if (query.isPending) return <p role="status">正在读取证据…</p>;
    if (query.isError)
        return (
            <div role="alert">
                <p>{error}</p>
                <button
                    type="button"
                    className="secondary-action"
                    onClick={() => void query.refetch()}
                >
                    重试读取
                </button>
            </div>
        );
    return <EvidenceBody key={query.data.id} snapshot={query.data} />;
}

export function EvidenceDrawer({
    incidentId,
    evidence,
}: {
    incidentId: string;
    evidence: EvidenceReference;
}) {
    const [open, setOpen] = useState(false);
    return (
        <Dialog.Root open={open} onOpenChange={setOpen}>
            <Dialog.Trigger asChild>
                <button
                    type="button"
                    className="evidence-open"
                    aria-label={`查看 ${evidence.sourceRef}`}
                >
                    查看详情 <span aria-hidden="true">↗</span>
                </button>
            </Dialog.Trigger>
            <Dialog.Portal>
                <Dialog.Overlay className="evidence-overlay" />
                <Dialog.Content className="evidence-drawer">
                    <header className="evidence-drawer-header">
                        <div>
                            <p className="eyebrow">EVIDENCE / READ ONLY</p>
                            <Dialog.Title>
                                证据 · {evidence.sourceRef}
                            </Dialog.Title>
                        </div>
                        <Dialog.Close asChild>
                            <button
                                type="button"
                                className="secondary-action"
                                aria-label="关闭证据详情"
                            >
                                关闭
                            </button>
                        </Dialog.Close>
                    </header>
                    <Dialog.Description>
                        查看当前服务授权范围内的脱敏快照与采集元数据。
                    </Dialog.Description>
                    <EvidenceContent
                        incidentId={incidentId}
                        evidenceId={evidence.id}
                    />
                </Dialog.Content>
            </Dialog.Portal>
        </Dialog.Root>
    );
}
