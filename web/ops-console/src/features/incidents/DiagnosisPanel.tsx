import type { CockpitDiagnosis, EvidenceReference } from "./incidentApi";
import { EvidenceDrawer } from "../evidence/EvidenceDrawer";

function percentage(value: number): string {
    return new Intl.NumberFormat("zh-CN", {
        style: "percent",
        maximumFractionDigits: 0,
    }).format(value);
}

export function DiagnosisPanel({
    incidentId,
    diagnosis,
    evidence,
}: {
    incidentId: string;
    diagnosis: CockpitDiagnosis;
    evidence: EvidenceReference[];
}) {
    const evidenceById = new Map(evidence.map((item) => [item.id, item]));

    return (
        <section
            className="control-card diagnosis-panel"
            aria-labelledby="diagnosis-title"
        >
            <header className="card-heading">
                <div>
                    <p className="eyebrow">DIAGNOSIS / EVIDENCE-BOUND</p>
                    <h2 id="diagnosis-title">{diagnosis.summary}</h2>
                </div>
                <span
                    className={`risk-chip risk-${diagnosis.riskLevel.toLowerCase()}`}
                >
                    {diagnosis.riskLevel}
                </span>
            </header>

            <ol className="hypothesis-list">
                {diagnosis.hypotheses.map((hypothesis) => (
                    <li key={`${hypothesis.rank}-${hypothesis.statement}`}>
                        <div className="hypothesis-rank" aria-hidden="true">
                            H{hypothesis.rank}
                        </div>
                        <div>
                            <strong>{hypothesis.statement}</strong>
                            <p className="confidence-note">
                                支持度 {percentage(hypothesis.confidence)} ·
                                仅作判断依据，不代表确定性
                            </p>
                            <div
                                className="evidence-links"
                                aria-label="支持证据"
                            >
                                {hypothesis.evidenceRefs.map((evidenceId) => {
                                    const item = evidenceById.get(evidenceId);
                                    return item ? (
                                        <a
                                            key={evidenceId}
                                            href={`#evidence-${evidenceId}`}
                                        >
                                            {item.sourceRef}
                                        </a>
                                    ) : null;
                                })}
                            </div>
                        </div>
                    </li>
                ))}
            </ol>

            {evidence.length > 0 ? (
                <ul className="evidence-index" aria-label="证据元数据">
                    {evidence.map((item) => (
                        <li
                            id={`evidence-${item.id}`}
                            key={item.id}
                            tabIndex={-1}
                        >
                            <strong>{item.sourceRef}</strong>
                            <span>
                                {item.sourceType} ·{" "}
                                {item.contentHash.slice(0, 18)}…
                            </span>
                            <time dateTime={item.capturedAt}>
                                {new Intl.DateTimeFormat("zh-CN", {
                                    hour: "2-digit",
                                    minute: "2-digit",
                                    second: "2-digit",
                                }).format(new Date(item.capturedAt))}
                            </time>
                            <EvidenceDrawer
                                incidentId={incidentId}
                                evidence={item}
                            />
                        </li>
                    ))}
                </ul>
            ) : null}

            <dl className="proposal-metadata">
                <div>
                    <dt>RUNBOOK</dt>
                    <dd>
                        {diagnosis.runbookKey ?? "未选择"}
                        {diagnosis.runbookVersion
                            ? ` · v${diagnosis.runbookVersion}`
                            : ""}
                    </dd>
                </div>
                <div>
                    <dt>PARAMETERS</dt>
                    <dd>{JSON.stringify(diagnosis.parameters)}</dd>
                </div>
                <div>
                    <dt>PROPOSAL HASH</dt>
                    <dd className="mono-value">
                        {diagnosis.proposalHash.slice(0, 12)}…
                    </dd>
                </div>
            </dl>
        </section>
    );
}
