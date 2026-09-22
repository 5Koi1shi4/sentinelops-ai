import type { RunbookDefinition, VersionDiff } from "./runbookApi";

function replicas(definition: RunbookDefinition | null) {
    const value = definition?.parameters.properties.replicas;
    return value ? `${value.minimum}–${value.maximum} 个实例` : "—";
}

function verification(definition: RunbookDefinition | null) {
    if (!definition) {
        return "—";
    }
    const { probe, successThreshold, attempts, intervalSeconds } =
        definition.verification;
    return `${probe} · ${attempts} 次尝试 · 间隔 ${intervalSeconds} 秒 · 连续成功 ${successThreshold} 次`;
}

function operation(definition: RunbookDefinition | null) {
    if (!definition) {
        return "—";
    }
    return `${definition.adapterId} / ${definition.steps[0]?.operation ?? "—"}`;
}

function ComparisonRow({
    label,
    before,
    after,
}: {
    label: string;
    before: string;
    after: string;
}) {
    const changed = before !== after;
    return (
        <div className={`runbook-diff-row${changed ? " is-changed" : ""}`}>
            <h3>{label}</h3>
            <div className="runbook-diff-values">
                <p>
                    <span>基准</span>
                    <strong>{before}</strong>
                </p>
                <p>
                    <span>提交版本</span>
                    <strong>{after}</strong>
                </p>
            </div>
            <span className="runbook-diff-state">
                {changed ? "有变化" : "保持不变"}
            </span>
        </div>
    );
}

export function RunbookVersionDiff({
    diff,
    currentDefinition,
    baselineDefinition = null,
    serviceLabel,
    initialBaseline = false,
}: {
    diff?: VersionDiff;
    currentDefinition: RunbookDefinition;
    baselineDefinition?: RunbookDefinition | null;
    serviceLabel: string;
    initialBaseline?: boolean;
}) {
    const before = diff?.beforeDefinition ?? baselineDefinition;
    const after = diff?.afterDefinition ?? currentDefinition;
    const beforeTarget = before
        ? `${before.runbookKey} · ${serviceLabel}`
        : "新 Runbook 基准";
    const afterTarget = `${after.runbookKey} · ${serviceLabel}`;

    return (
        <section
            aria-labelledby="runbook-diff-title"
            className="runbook-panel runbook-diff"
        >
            <header className="runbook-panel-heading">
                <div>
                    <p className="eyebrow">VERSION / STRUCTURAL DIFF</p>
                    <h2 id="runbook-diff-title">发布前结构差异</h2>
                </div>
                <span className="runbook-baseline-chip">
                    {initialBaseline ? "首个发布基准" : "对照最近已发布版本"}
                </span>
            </header>
            <div className="runbook-diff-grid">
                <ComparisonRow
                    label="执行目标"
                    before={beforeTarget}
                    after={afterTarget}
                />
                <ComparisonRow
                    label="风险等级"
                    before={before?.risk ?? "— 新版本基准"}
                    after={after.risk}
                />
                <ComparisonRow
                    label="执行器与操作"
                    before={operation(before)}
                    after={operation(after)}
                />
                <ComparisonRow
                    label="参数范围"
                    before={replicas(before)}
                    after={replicas(after)}
                />
                <ComparisonRow
                    label="验证规则"
                    before={verification(before)}
                    after={verification(after)}
                />
                <ComparisonRow
                    label="回滚"
                    before={before ? "无（固定）" : "— 新版本基准"}
                    after="无（固定）"
                />
            </div>
            {diff?.markdownChanged ? (
                <details className="runbook-markdown-diff">
                    <summary>操作说明有变化</summary>
                    <div className="runbook-markdown-columns">
                        <div>
                            <h3>基准正文</h3>
                            <pre>{diff.beforeMarkdown}</pre>
                        </div>
                        <div>
                            <h3>提交正文</h3>
                            <pre>{diff.afterMarkdown}</pre>
                        </div>
                    </div>
                </details>
            ) : null}
        </section>
    );
}
