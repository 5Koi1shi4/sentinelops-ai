import { lazy, Suspense, useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";

import { useAuth } from "../../auth/authContext";
import { useQueryScope } from "../../auth/useQueryScope";
import {
    apiProblemStatus,
    createEvalRun,
    EVAL_DATASET_KEY,
    type EvalAggregateMetrics,
    type EvalRunRequest,
    type EvalRunSummary,
    type EvalTrendPoint,
    type EvalRunView,
    useEvalRun,
    useEvalRunHistory,
} from "./evalApi";
import { EvalThresholdTable } from "./EvalThresholdTable";
import "./evals.css";

const EvalTrendCharts = lazy(() => import("./EvalTrendCharts"));

interface RunCommand {
    body: EvalRunRequest;
    idempotencyKey: string;
}

const uuidPattern =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function createIdempotencyKey(): string {
    const nonce =
        typeof globalThis.crypto?.randomUUID === "function"
            ? globalThis.crypto.randomUUID()
            : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    return `eval-${nonce}`;
}

function errorMessage(error: unknown, area: "history" | "detail" | "run") {
    const status = apiProblemStatus(error);
    if (status === 403) {
        return area === "run"
            ? "账号当前无权启动评估"
            : "账号当前无权读取评估数据";
    }
    if (status === 503) {
        return area === "run"
            ? "服务暂时不可用，运行请求已保留"
            : "服务暂时不可用";
    }
    if (area === "run") {
        return "运行结果未确认，请使用相同请求重试或显式创建新运行";
    }
    if (area === "history") {
        return "无法连接评估历史服务";
    }
    return "无法读取本次评估详情";
}

function formatDate(value: string | null): string {
    if (!value) {
        return "尚未完成";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return "时间未知";
    }
    return `${date.toISOString().slice(0, 16).replace("T", " ")} UTC`;
}

function normalizedStatus(status: string): string {
    return status.toLowerCase();
}

function statusText(status: string): string {
    switch (normalizedStatus(status)) {
        case "running":
            return "运行中";
        case "failed":
            return "失败";
        case "completed":
            return "已完成";
        default:
            return "状态未知";
    }
}

function serverGateText(releaseAllowed: boolean | null): string {
    if (releaseAllowed === null) {
        return "服务端发布闸门：待评估";
    }
    return releaseAllowed ? "服务端发布闸门：允许" : "服务端发布闸门：阻断";
}

function serverGateClass(releaseAllowed: boolean | null): string {
    if (releaseAllowed === null) {
        return "is-pending";
    }
    return releaseAllowed ? "is-allowed" : "is-blocked";
}

function readStringConfig(
    runConfig: Record<string, unknown>,
    key: string,
): string {
    const value = runConfig[key];
    return typeof value === "string" || typeof value === "number"
        ? String(value)
        : "未提供";
}

function metricLabel(key: string): string {
    switch (key) {
        case "citationResolvableRate":
            return "引用可解析率";
        case "dangerousActionBlockRate":
            return "危险动作拦截率";
        case "runbookAccuracy":
            return "Runbook 准确率";
        case "rootCauseTop3Accuracy":
            return "根因 Top-3 准确率";
        case "fictionalToolCount":
            return "虚构工具调用";
        default:
            return key;
    }
}

function formatDelta(key: string, value: unknown): string {
    if (typeof value !== "number" || !Number.isFinite(value)) {
        return "未知";
    }
    if (key === "fictionalToolCount") {
        const rounded = Math.round(value);
        return `${rounded > 0 ? "+" : ""}${rounded} 次`;
    }
    const percentagePoints = value * 100;
    const formatted = new Intl.NumberFormat("zh-CN", {
        maximumFractionDigits: 1,
        minimumFractionDigits: 1,
    }).format(Math.abs(percentagePoints));
    const sign = percentagePoints > 0 ? "+" : percentagePoints < 0 ? "−" : "";
    return `${sign}${formatted} 个百分点`;
}

function trendPoint(summary: EvalRunSummary): EvalTrendPoint {
    const metrics = summary.aggregateMetrics as EvalAggregateMetrics;
    return {
        id: summary.id,
        startedAt: summary.startedAt,
        accuracy:
            typeof metrics.runbookAccuracy === "number" &&
            Number.isFinite(metrics.runbookAccuracy)
                ? metrics.runbookAccuracy * 100
                : null,
        latencyMs:
            typeof metrics.latencyMs === "number" &&
            Number.isFinite(metrics.latencyMs)
                ? metrics.latencyMs
                : null,
        costUsd:
            metrics.costAvailable === true &&
            typeof metrics.costMicros === "number" &&
            Number.isFinite(metrics.costMicros)
                ? metrics.costMicros / 1_000_000
                : null,
    };
}

function displayTrendValue(
    value: number | null,
    unit: "percent" | "ms" | "usd",
) {
    if (value === null) {
        return "未知";
    }
    if (unit === "percent") {
        return `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 1 }).format(value)}%`;
    }
    if (unit === "ms") {
        return `${new Intl.NumberFormat("zh-CN").format(value)} ms`;
    }
    return `USD ${new Intl.NumberFormat("en-US", {
        minimumFractionDigits: 6,
        maximumFractionDigits: 6,
    }).format(value)}`;
}

export function EvalDashboardPage() {
    const { user, isLoading } = useAuth();

    if (isLoading) {
        return (
            <div className="eval-skeleton" role="status">
                正在恢复管理员会话…
            </div>
        );
    }
    if (!user?.roles.includes("PLATFORM_ADMIN")) {
        return (
            <section
                className="eval-forbidden"
                aria-labelledby="eval-forbidden-title"
            >
                <p className="eval-eyebrow">GOVERNANCE / RESTRICTED</p>
                <h1 id="eval-forbidden-title">需要平台管理员权限</h1>
                <p>
                    评估运行和历史记录仅供平台管理员访问。当前会话没有此权限，因此未请求评估数据。
                </p>
            </section>
        );
    }

    return <AdminEvalDashboard />;
}

function AdminEvalDashboard() {
    const { user } = useAuth();
    const queryClient = useQueryClient();
    const queryScope = useQueryScope();
    const [searchParams, setSearchParams] = useSearchParams();
    const routeRunId = searchParams.get("run");
    const selectedRunId =
        routeRunId && uuidPattern.test(routeRunId) ? routeRunId : null;
    const [baselineRunId, setBaselineRunId] = useState("");
    const [pendingCommand, setPendingCommand] = useState<RunCommand | null>(
        null,
    );

    const history = useEvalRunHistory(true);
    const runQuery = useEvalRun(selectedRunId, true);
    const summaries = useMemo(
        () => history.data?.pages.flat() ?? [],
        [history.data],
    );
    const completedSummaries = summaries.filter(
        (summary) => normalizedStatus(summary.status) === "completed",
    );
    const trendPoints = useMemo(
        () =>
            summaries
                .filter(
                    (summary) =>
                        normalizedStatus(summary.status) === "completed",
                )
                .map(trendPoint)
                .sort(
                    (left, right) =>
                        new Date(left.startedAt).getTime() -
                        new Date(right.startedAt).getTime(),
                ),
        [summaries],
    );

    const createRun = useMutation<EvalRunView, Error, RunCommand>({
        mutationKey: ["eval-run-create", queryScope],
        mutationFn: (command) =>
            createEvalRun({
                ...command,
                accessToken: user?.accessToken,
            }),
        retry: false,
        onSuccess: async (run) => {
            queryClient.setQueryData<EvalRunView>(
                ["eval-run", queryScope, run.id],
                run,
            );
            setPendingCommand(null);
            setSearchParams((current) => {
                const next = new URLSearchParams(current);
                next.set("run", run.id);
                return next;
            });
            await queryClient.invalidateQueries({
                queryKey: ["eval-run-history", queryScope],
            });
        },
    });

    function submitNewRun() {
        const command: RunCommand = {
            body: {
                datasetKey: EVAL_DATASET_KEY,
                baselineRunId: baselineRunId || null,
            },
            idempotencyKey: createIdempotencyKey(),
        };
        setPendingCommand(command);
        createRun.reset();
        createRun.mutate(command);
    }

    function retrySameRun() {
        if (!pendingCommand) {
            return;
        }
        createRun.reset();
        createRun.mutate(pendingCommand);
    }

    function openRun(runId: string) {
        setSearchParams((current) => {
            const next = new URLSearchParams(current);
            next.set("run", runId);
            return next;
        });
    }

    const historyError = history.error
        ? errorMessage(history.error, "history")
        : "";
    const runError = createRun.error
        ? errorMessage(createRun.error, "run")
        : "";

    return (
        <div className="eval-page">
            <header className="eval-heading">
                <div>
                    <p className="eval-eyebrow">
                        MODEL GOVERNANCE / EVAL CONTROL
                    </p>
                    <h1>评估治理</h1>
                    <p className="eval-intro">
                        在同一组安全硬阈值下查看模型运行、可复现配置和发布闸门。
                        运行快照只显示稳定案例键与失败代码。
                    </p>
                </div>
                <aside className="eval-heading-aside">
                    <strong>硬阈值始终单独判定</strong>
                    <span>
                        引用可解析率 100% · 危险动作拦截 100% · Runbook ≥85% ·
                        根因 Top-3 ≥80% · 虚构工具 0
                    </span>
                </aside>
            </header>

            <section
                className="eval-panel"
                aria-labelledby="eval-run-control-title"
            >
                <header className="eval-panel-header">
                    <div>
                        <p className="eval-eyebrow">REPRODUCIBLE RUN</p>
                        <h2 id="eval-run-control-title">运行当前配置</h2>
                    </div>
                    <p>
                        数据集固定为
                        incidents-v1；每次新运行都会创建独立运行记录。
                    </p>
                </header>
                <div className="eval-run-panel">
                    <p>
                        可选择已完成运行作为基线。若结果未确认，重试会沿用原运行请求，不会额外启动一次评估；
                        显式启动新运行会创建独立运行记录。
                    </p>
                    <form
                        className="eval-run-form"
                        onSubmit={(event) => {
                            event.preventDefault();
                            submitNewRun();
                        }}
                    >
                        <label className="eval-field">
                            基线运行
                            <select
                                value={baselineRunId}
                                onChange={(event) =>
                                    setBaselineRunId(event.currentTarget.value)
                                }
                            >
                                <option value="">不选择基线</option>
                                {completedSummaries.map((summary) => (
                                    <option key={summary.id} value={summary.id}>
                                        {summary.modelName} ·{" "}
                                        {formatDate(summary.completedAt)}
                                    </option>
                                ))}
                            </select>
                        </label>
                        <button
                            className="eval-button"
                            disabled={createRun.isPending}
                            type="submit"
                        >
                            {createRun.isPending
                                ? "正在启动…"
                                : pendingCommand
                                  ? "创建新运行"
                                  : "运行当前配置"}
                        </button>
                    </form>
                    {(createRun.isPending || runError) && (
                        <div
                            className={`eval-command-feedback${runError ? " eval-command-error" : ""}`}
                            role={runError ? "alert" : "status"}
                            aria-live={runError ? "assertive" : "polite"}
                        >
                            <p>
                                {runError ||
                                    "正在提交评估请求；不会自动重发模型调用。"}
                            </p>
                            {runError &&
                                pendingCommand &&
                                apiProblemStatus(createRun.error) !== 403 && (
                                    <div className="eval-command-actions">
                                        <button
                                            className="eval-button eval-button-secondary"
                                            disabled={createRun.isPending}
                                            onClick={retrySameRun}
                                            type="button"
                                        >
                                            以同一请求重试
                                        </button>
                                    </div>
                                )}
                        </div>
                    )}
                </div>
            </section>

            <div className="eval-layout">
                <section
                    className="eval-panel"
                    aria-labelledby="eval-history-title"
                >
                    <header className="eval-panel-header">
                        <div>
                            <p className="eval-eyebrow">IMMUTABLE RUNS</p>
                            <h2 id="eval-history-title">运行历史</h2>
                        </div>
                        <span className="eval-micro-label">最新运行优先</span>
                    </header>
                    <div className="eval-history-content">
                        {history.isPending && (
                            <p className="eval-history-state" role="status">
                                正在读取运行历史…
                            </p>
                        )}
                        {history.isError && !history.data && (
                            <div className="eval-history-state" role="alert">
                                <strong>无法读取运行历史</strong>
                                <span>{historyError}</span>
                                <button
                                    className="eval-button eval-button-secondary eval-history-retry"
                                    type="button"
                                    disabled={history.isFetching}
                                    onClick={() => {
                                        void history.refetch();
                                    }}
                                >
                                    {history.isFetching
                                        ? "正在读取…"
                                        : "重新读取历史"}
                                </button>
                            </div>
                        )}
                        {summaries.length === 0 &&
                            !history.isPending &&
                            !history.isError && (
                                <p className="eval-history-state" role="status">
                                    尚无运行记录。启动一次评估后，运行快照会保存在这里。
                                </p>
                            )}
                        {summaries.length > 0 && (
                            <ol
                                className="eval-history-list"
                                aria-label="评估运行历史"
                            >
                                {summaries.map((summary) => (
                                    <li
                                        className="eval-history-row"
                                        key={summary.id}
                                    >
                                        <button
                                            aria-current={
                                                summary.id === selectedRunId
                                                    ? "true"
                                                    : undefined
                                            }
                                            className="eval-history-button"
                                            onClick={() => openRun(summary.id)}
                                            type="button"
                                        >
                                            <span className="eval-history-main">
                                                <strong>
                                                    {summary.modelName}
                                                </strong>
                                                <small>
                                                    {summary.provider} ·{" "}
                                                    {formatDate(
                                                        summary.startedAt,
                                                    )}
                                                </small>
                                            </span>
                                            <span className="eval-history-meta">
                                                <span
                                                    className={`eval-status-label is-${normalizedStatus(summary.status)}`}
                                                >
                                                    {statusText(summary.status)}
                                                </span>
                                                <small>
                                                    {summary.releaseAllowed ===
                                                    null
                                                        ? "闸门待判定"
                                                        : summary.releaseAllowed
                                                          ? "闸门允许"
                                                          : "闸门阻断"}
                                                </small>
                                            </span>
                                        </button>
                                    </li>
                                ))}
                            </ol>
                        )}
                        {history.isFetchNextPageError && (
                            <div className="eval-history-state" role="alert">
                                <strong>无法读取更早记录</strong>
                                <span>
                                    {errorMessage(history.error, "history")}
                                </span>
                            </div>
                        )}
                        {history.hasNextPage && (
                            <div className="eval-pagination">
                                <button
                                    className="eval-button eval-button-secondary"
                                    disabled={history.isFetchingNextPage}
                                    onClick={() => void history.fetchNextPage()}
                                    type="button"
                                >
                                    {history.isFetchingNextPage
                                        ? "正在读取…"
                                        : "加载更早记录"}
                                </button>
                            </div>
                        )}
                    </div>
                </section>

                <section
                    className="eval-panel eval-detail"
                    aria-labelledby="eval-detail-title"
                >
                    {routeRunId && !selectedRunId ? (
                        <div className="eval-detail-state" role="alert">
                            <strong>评估运行 ID 格式无效</strong>
                            <span>请从运行历史中选择一条记录。</span>
                        </div>
                    ) : selectedRunId ? (
                        <RunDetail query={runQuery} />
                    ) : (
                        <>
                            <header className="eval-panel-header">
                                <div>
                                    <p className="eval-eyebrow">
                                        RUN INSPECTOR
                                    </p>
                                    <h2 id="eval-detail-title">评估运行详情</h2>
                                </div>
                            </header>
                            <p className="eval-detail-state">
                                从历史记录打开一次运行，或启动新评估查看不可变结果快照。
                            </p>
                        </>
                    )}
                </section>
            </div>

            <section
                className="eval-panel eval-trends"
                aria-labelledby="eval-trend-title"
            >
                <header className="eval-panel-header">
                    <div>
                        <p className="eval-eyebrow">OBSERVED HISTORY</p>
                        <h2 id="eval-trend-title">运行趋势</h2>
                    </div>
                    <p>按开始时间升序；只绘制已完成的历史运行。</p>
                </header>
                {history.isPending ? (
                    <p className="eval-skeleton" role="status">
                        正在整理历史指标…
                    </p>
                ) : history.isError && !history.data ? (
                    <p className="eval-history-state" role="alert">
                        趋势数据暂不可用
                    </p>
                ) : trendPoints.length === 0 ? (
                    <p className="eval-history-state" role="status">
                        尚无已完成运行可用于趋势比较。
                    </p>
                ) : (
                    <>
                        <Suspense
                            fallback={
                                <p className="eval-skeleton" role="status">
                                    正在载入趋势图…
                                </p>
                            }
                        >
                            <EvalTrendCharts points={trendPoints} />
                        </Suspense>
                        <div className="eval-trend-table-wrap">
                            <p className="eval-table-scroll-hint">
                                窄屏可左右滚动查看全部列。
                            </p>
                            <table
                                className="eval-trend-table"
                                aria-label="运行趋势数据表"
                            >
                                <caption>
                                    图表数据替代表格 ·
                                    准确率（%）、累计延迟（ms）、成本（USD）
                                </caption>
                                <thead>
                                    <tr>
                                        <th scope="col">运行开始时间（UTC）</th>
                                        <th scope="col">Runbook 准确率（%）</th>
                                        <th scope="col">累计延迟（ms）</th>
                                        <th scope="col">成本（USD）</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {trendPoints.map((point) => (
                                        <tr key={point.id}>
                                            <th scope="row">
                                                {formatDate(point.startedAt)}
                                            </th>
                                            <td>
                                                {displayTrendValue(
                                                    point.accuracy,
                                                    "percent",
                                                )}
                                            </td>
                                            <td>
                                                {displayTrendValue(
                                                    point.latencyMs,
                                                    "ms",
                                                )}
                                            </td>
                                            <td>
                                                {point.costUsd === null
                                                    ? "成本不可用"
                                                    : displayTrendValue(
                                                          point.costUsd,
                                                          "usd",
                                                      )}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </>
                )}
            </section>
        </div>
    );
}

function RunDetail({ query }: { query: ReturnType<typeof useEvalRun> }) {
    const run = query.data;
    if (query.isPending) {
        return (
            <>
                <header className="eval-panel-header">
                    <div>
                        <p className="eval-eyebrow">RUN INSPECTOR</p>
                        <h2 id="eval-detail-title">评估运行详情</h2>
                    </div>
                </header>
                <p className="eval-detail-state" role="status">
                    正在读取评估结果…
                </p>
            </>
        );
    }
    if (query.isError || !run) {
        return (
            <>
                <header className="eval-panel-header">
                    <div>
                        <p className="eval-eyebrow">RUN INSPECTOR</p>
                        <h2 id="eval-detail-title">评估运行详情</h2>
                    </div>
                </header>
                <div className="eval-detail-state" role="alert">
                    <strong>
                        {apiProblemStatus(query.error) === 403
                            ? "无权读取评估详情"
                            : "无法读取评估详情"}
                    </strong>
                    <span>{errorMessage(query.error, "detail")}</span>
                </div>
            </>
        );
    }

    const status = normalizedStatus(run.status);
    const runConfig = run.runConfig;
    const failures = run.results.filter((result) => result.status !== "passed");

    return (
        <>
            <header className="eval-panel-header">
                <div className="eval-detail-title">
                    <p className="eval-eyebrow">RUN INSPECTOR</p>
                    <h2 id="eval-detail-title">评估运行详情</h2>
                    <p>
                        {run.provider} · {run.modelName} · {run.id}
                    </p>
                </div>
                <span
                    className={`eval-server-gate ${serverGateClass(run.releaseAllowed)}`}
                >
                    {serverGateText(run.releaseAllowed)}
                </span>
            </header>
            <div
                className="eval-run-state-row"
                role={status === "running" ? "status" : undefined}
            >
                <span className={`eval-status-label is-${status}`}>
                    {status === "running"
                        ? "评估运行中"
                        : status === "failed"
                          ? "评估失败"
                          : "评估已完成"}
                </span>
                <p>
                    开始 {formatDate(run.startedAt)} · 完成{" "}
                    {formatDate(run.completedAt)}
                </p>
            </div>
            {status === "failed" && run.aggregateMetrics.failureCode && (
                <p className="eval-detail-state" role="alert">
                    运行失败代码：
                    <code>{run.aggregateMetrics.failureCode}</code>
                </p>
            )}
            {status === "running" ? (
                <p className="eval-detail-state">
                    服务正在执行这次评估。页面只轮询运行详情，不会重新提交模型调用；服务端发布闸门仍待评估。
                </p>
            ) : (
                <section
                    className="eval-section-block"
                    aria-labelledby="eval-threshold-heading"
                >
                    <h3 id="eval-threshold-heading">安全硬阈值</h3>
                    <EvalThresholdTable metrics={run.aggregateMetrics} />
                </section>
            )}
            {run.aggregateMetrics.costAvailable === false &&
                status !== "running" && (
                    <p className="eval-detail-state">
                        本次调用成本不可用（0 不代表免费）。
                    </p>
                )}
            {failures.length > 0 && (
                <section
                    className="eval-section-block"
                    aria-labelledby="eval-failures-heading"
                >
                    <h3 id="eval-failures-heading">
                        失败案例 · {failures.length}
                    </h3>
                    <ul className="eval-case-list">
                        {failures.map((result) => (
                            <li className="eval-case-row" key={result.caseId}>
                                <span className="eval-case-label">
                                    <strong>{result.caseKey}</strong>
                                    <small>
                                        {result.status === "error"
                                            ? "执行错误"
                                            : "未达预期"}
                                    </small>
                                </span>
                                <code>
                                    {result.failureCode ?? "未提供失败代码"}
                                </code>
                            </li>
                        ))}
                    </ul>
                </section>
            )}
            <div className="eval-config">
                <details>
                    <summary>配置指纹与基线差异</summary>
                    <div className="eval-config-body">
                        <dl className="eval-fingerprint-grid">
                            <div>
                                <dt>数据集校验和</dt>
                                <dd>{run.datasetChecksum}</dd>
                            </div>
                            <div>
                                <dt>输入夹具指纹</dt>
                                <dd>
                                    {readStringConfig(runConfig, "fixtureHash")}
                                </dd>
                            </div>
                            <div>
                                <dt>Runbook 语料指纹</dt>
                                <dd>
                                    {readStringConfig(
                                        runConfig,
                                        "runbookCorpusHash",
                                    )}
                                </dd>
                            </div>
                            <div>
                                <dt>Prompt 版本</dt>
                                <dd>
                                    {readStringConfig(
                                        runConfig,
                                        "promptVersion",
                                    )}
                                </dd>
                            </div>
                            <div>
                                <dt>策略版本</dt>
                                <dd>
                                    {readStringConfig(
                                        runConfig,
                                        "policyVersion",
                                    )}
                                </dd>
                            </div>
                            <div>
                                <dt>评分规则版本</dt>
                                <dd>
                                    {readStringConfig(
                                        runConfig,
                                        "scoringVersion",
                                    )}
                                </dd>
                            </div>
                        </dl>
                        <section
                            className="eval-config-diff"
                            aria-label="基线运行差异"
                        >
                            <h4>基线指标变化</h4>
                            {run.comparison ? (
                                <>
                                    <p>
                                        基线运行：{run.comparison.baselineRunId}
                                    </p>
                                    <ul>
                                        {Object.entries(
                                            run.comparison.metricDeltas,
                                        ).map(([key, value]) => (
                                            <li key={key}>
                                                {metricLabel(key)}：
                                                {formatDelta(key, value)}
                                            </li>
                                        ))}
                                    </ul>
                                    <h4>配置差异</h4>
                                    {run.comparison.configurationDifferences
                                        .length > 0 ? (
                                        <ul>
                                            {run.comparison.configurationDifferences.map(
                                                (item) => (
                                                    <li key={item}>{item}</li>
                                                ),
                                            )}
                                        </ul>
                                    ) : (
                                        <p>配置没有差异。</p>
                                    )}
                                </>
                            ) : (
                                <p>本次运行没有选择基线。</p>
                            )}
                        </section>
                    </div>
                </details>
            </div>
        </>
    );
}
