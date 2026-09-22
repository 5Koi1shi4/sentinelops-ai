import { Link } from "react-router-dom";

import { useIncidents } from "./incidentApi";

const terminalStatuses = new Set(["RESOLVED", "SUPPRESSED"]);

export function IncidentListPage() {
    const incidents = useIncidents({});
    const items = incidents.data?.items ?? [];
    const activeCount = items.filter(
        (incident) => !terminalStatuses.has(incident.status),
    ).length;

    return (
        <>
            <section className="incident-intro" id="incident-center">
                <div>
                    <p className="eyebrow">INCIDENT COMMAND / LIVE QUEUE</p>
                    <h1>事故中心</h1>
                    <p className="lede">
                        从告警信号到验证恢复，每一步都有证据、版本和责任边界。
                    </p>
                </div>
                <dl className="readiness-panel" aria-label="系统就绪状态">
                    <div>
                        <dt>OPEN INCIDENTS</dt>
                        <dd>{String(activeCount).padStart(2, "0")}</dd>
                    </div>
                    <div>
                        <dt>CONTROL STATUS</dt>
                        <dd className="ready-value">
                            {incidents.isError ? "DEGRADED" : "READY"}
                        </dd>
                    </div>
                </dl>
            </section>

            <section className="queue-panel" aria-labelledby="queue-title">
                <header className="queue-header">
                    <div>
                        <p className="eyebrow">ACTIVE QUEUE</p>
                        <h2 id="queue-title">事故队列</h2>
                    </div>
                    <span className="queue-count">{activeCount} ACTIVE</span>
                </header>
                {incidents.isPending ? (
                    <div className="connection-state" role="status">
                        <span className="radar-mark" aria-hidden="true">
                            <span />
                        </span>
                        <p>正在连接控制平面…</p>
                        <small>读取当前服务范围内的事故。</small>
                    </div>
                ) : null}
                {incidents.isError ? (
                    <div className="connection-state" role="alert">
                        <p>无法读取事故队列</p>
                        <small>{incidents.error.message}</small>
                    </div>
                ) : null}
                {!incidents.isPending &&
                !incidents.isError &&
                items.length === 0 ? (
                    <div className="connection-state" role="status">
                        <p>当前没有事故</p>
                        <small>
                            新的告警完成归并和风险分级后会出现在这里。
                        </small>
                    </div>
                ) : null}
                {items.length > 0 ? (
                    <ul className="incident-table" aria-label="事故列表">
                        {items.map((incident) => (
                            <li key={incident.id}>
                                <Link to={`/incidents/${incident.id}`}>
                                    <span
                                        className={`severity-mark ${incident.severity}`}
                                    >
                                        {incident.severity.toUpperCase()}
                                    </span>
                                    <span className="incident-name">
                                        <strong>{incident.title}</strong>
                                        <small>{incident.serviceKey}</small>
                                    </span>
                                    <span className="incident-occurrences">
                                        ×{incident.occurrenceCount}
                                    </span>
                                    <span
                                        className={`state-chip state-${incident.status.toLowerCase()}`}
                                    >
                                        {incident.status}
                                    </span>
                                    <time dateTime={incident.updatedAt}>
                                        {new Intl.DateTimeFormat("zh-CN", {
                                            hour: "2-digit",
                                            minute: "2-digit",
                                        }).format(new Date(incident.updatedAt))}
                                    </time>
                                </Link>
                            </li>
                        ))}
                    </ul>
                ) : null}
            </section>
        </>
    );
}
