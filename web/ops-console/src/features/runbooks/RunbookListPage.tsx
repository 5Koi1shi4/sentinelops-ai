import { Link } from "react-router-dom";

import { ApiProblem } from "../../api/client";
import { useAuth } from "../../auth/authContext";
import { useRunbooks } from "./runbookApi";
import "./runbooks.css";

function lifecycleLabel(lifecycle: string) {
    switch (lifecycle) {
        case "published":
            return "已发布";
        case "retired":
            return "已退役";
        default:
            return "草稿";
    }
}

export function RunbookListPage() {
    const { user } = useAuth();
    const runbooks = useRunbooks();
    const canAuthor = Boolean(
        user?.roles.some(
            (role) => role === "RUNBOOK_ADMIN" || role === "PLATFORM_ADMIN",
        ),
    );
    const items = (runbooks.data?.pages.flat() ?? []).filter(
        (runbook) => canAuthor || runbook.lifecycle === "published",
    );

    return (
        <main className="runbook-page" id="main-content">
            <header className="runbook-page-heading">
                <div>
                    <p className="eyebrow">
                        RUNBOOK GOVERNANCE / SERVICE CATALOG
                    </p>
                    <h1>Runbook 管理</h1>
                    <p className="runbook-lede">
                        查看服务恢复步骤、责任团队与不可变版本，所有执行参数都受控且可审查。
                    </p>
                </div>
                {canAuthor && !runbooks.isError ? (
                    <Link
                        className="primary-action runbook-heading-action"
                        to="/runbooks/new"
                    >
                        新建 Runbook
                    </Link>
                ) : null}
            </header>

            <section
                className="runbook-panel"
                aria-labelledby="runbook-list-title"
            >
                <header className="runbook-panel-heading">
                    <div>
                        <p className="eyebrow">CONTROLLED PROCEDURES</p>
                        <h2 id="runbook-list-title">服务运行手册</h2>
                    </div>
                    {!runbooks.isPending && !runbooks.isError ? (
                        <span className="runbook-list-count">
                            {items.length} 个可见条目
                        </span>
                    ) : null}
                </header>

                {runbooks.isPending ? (
                    <div className="runbook-state" role="status">
                        <strong>正在读取 Runbook</strong>
                        <span>按你的服务范围读取可用版本。</span>
                    </div>
                ) : null}

                {runbooks.isError ? (
                    <div className="runbook-state is-error" role="alert">
                        <strong>
                            {runbooks.error instanceof ApiProblem &&
                            runbooks.error.status === 403
                                ? "无法读取当前服务范围"
                                : "无法读取 Runbook 列表"}
                        </strong>
                        <span>
                            {runbooks.error.message ||
                                "请求失败，请检查权限或稍后重试。"}
                        </span>
                        <button
                            className="secondary-action"
                            type="button"
                            onClick={() => void runbooks.refetch()}
                        >
                            重新读取
                        </button>
                    </div>
                ) : null}

                {!runbooks.isPending &&
                !runbooks.isError &&
                items.length === 0 ? (
                    <div className="runbook-state">
                        <strong>还没有 Runbook</strong>
                        <span>
                            {canAuthor
                                ? "先登记服务、责任团队和受控的恢复步骤。"
                                : "当前服务范围内还没有已发布的 Runbook。"}
                        </span>
                        {canAuthor ? (
                            <Link
                                className="secondary-action"
                                to="/runbooks/new"
                            >
                                新建 Runbook
                            </Link>
                        ) : null}
                    </div>
                ) : null}

                {items.length > 0 ? (
                    <ul className="runbook-list" aria-label="Runbook 列表">
                        {items.map((runbook) => {
                            const content = (
                                <>
                                    <span
                                        className={`runbook-risk-chip risk-${runbook.riskLevel.toLowerCase()}`}
                                    >
                                        {runbook.riskLevel}
                                    </span>
                                    <span className="runbook-list-name">
                                        <strong>{runbook.displayName}</strong>
                                        <small>
                                            {runbook.runbookKey} ·{" "}
                                            {runbook.serviceKey}
                                        </small>
                                    </span>
                                    <span className="runbook-list-owner">
                                        <small>责任团队</small>
                                        <strong>{runbook.ownerTeam}</strong>
                                    </span>
                                    <span
                                        className={`runbook-lifecycle state-${runbook.lifecycle}`}
                                    >
                                        {lifecycleLabel(runbook.lifecycle)}
                                    </span>
                                    <span className="runbook-version-number">
                                        {runbook.latestVersionNumber
                                            ? `v${runbook.latestVersionNumber}`
                                            : "尚无版本"}
                                    </span>
                                </>
                            );
                            const versionPath = runbook.latestVersionId
                                ? `/runbooks/${encodeURIComponent(runbook.runbookKey)}/versions/${runbook.latestVersionId}`
                                : null;
                            return (
                                <li key={runbook.id}>
                                    {versionPath ? (
                                        <Link to={versionPath}>{content}</Link>
                                    ) : (
                                        <div className="runbook-list-row">
                                            {content}
                                        </div>
                                    )}
                                </li>
                            );
                        })}
                    </ul>
                ) : null}

                {runbooks.hasNextPage ? (
                    <footer className="runbook-list-footer">
                        <button
                            className="secondary-action"
                            type="button"
                            disabled={runbooks.isFetchingNextPage}
                            onClick={() => void runbooks.fetchNextPage()}
                        >
                            {runbooks.isFetchingNextPage
                                ? "正在读取…"
                                : "加载更多 Runbook"}
                        </button>
                    </footer>
                ) : null}
            </section>
        </main>
    );
}
