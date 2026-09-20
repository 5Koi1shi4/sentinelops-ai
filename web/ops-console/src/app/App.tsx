const navigation = [
    { index: "01", label: "事故中心", href: "#incident-center", current: true },
    { index: "02", label: "审批工作台", href: "#approvals" },
    { index: "03", label: "Runbooks", href: "#runbooks" },
    { index: "04", label: "AI 评测", href: "#evaluations" },
];

const signalStages = [
    { index: "01", label: "信号接入", state: "监听中", active: true },
    { index: "02", label: "事故归并", state: "待命" },
    { index: "03", label: "决策审批", state: "待命" },
    { index: "04", label: "安全执行", state: "待命" },
];

export function App() {
    return (
        <div className="console-shell">
            <a className="skip-link" href="#main-content">
                跳至主要内容
            </a>

            <aside className="command-rail">
                <div className="brand-lockup">
                    <span className="brand-mark" aria-hidden="true">
                        S
                    </span>
                    <span>
                        <strong>SentinelOps</strong>
                        <small>AI CONTROL PLANE</small>
                    </span>
                </div>

                <div className="rail-caption">
                    <span>INCIDENT COMMAND</span>
                    <span className="live-indicator">
                        <span className="status-dot" aria-hidden="true" /> LIVE
                    </span>
                </div>

                <nav className="primary-navigation" aria-label="主导航">
                    <ol>
                        {navigation.map((item) => (
                            <li key={item.index}>
                                <a
                                    className={
                                        item.current ? "is-current" : undefined
                                    }
                                    href={item.href}
                                    aria-current={
                                        item.current ? "page" : undefined
                                    }
                                >
                                    <span
                                        className="nav-index"
                                        aria-hidden="true"
                                    >
                                        {item.index}
                                    </span>
                                    <span>{item.label}</span>
                                    <span
                                        className="nav-arrow"
                                        aria-hidden="true"
                                    >
                                        →
                                    </span>
                                </a>
                            </li>
                        ))}
                    </ol>
                </nav>

                <div className="environment-card">
                    <span className="environment-label">ENVIRONMENT</span>
                    <strong>Stage 1 · Demo</strong>
                    <span>本地控制平面</span>
                </div>
            </aside>

            <main id="main-content" className="workspace">
                <header className="topbar">
                    <p>
                        <span className="status-dot" aria-hidden="true" />{" "}
                        控制平面在线
                    </p>
                    <p className="topbar-meta">CN / UTC+08 · 只读观察模式</p>
                </header>

                <section className="incident-intro" id="incident-center">
                    <div>
                        <p className="eyebrow">INCIDENT COMMAND / LIVE QUEUE</p>
                        <h1>事故中心</h1>
                        <p className="lede">
                            汇聚告警信号，构建事故上下文，并将每一次高风险操作置于人工审批之下。
                        </p>
                    </div>

                    <dl className="readiness-panel" aria-label="系统就绪状态">
                        <div>
                            <dt>OPEN INCIDENTS</dt>
                            <dd>00</dd>
                        </div>
                        <div>
                            <dt>CONTROL STATUS</dt>
                            <dd className="ready-value">READY</dd>
                        </div>
                    </dl>
                </section>

                <ol className="signal-rail" aria-label="事故响应阶段">
                    {signalStages.map((stage) => (
                        <li
                            className={stage.active ? "is-active" : undefined}
                            key={stage.index}
                        >
                            <span className="signal-index">{stage.index}</span>
                            <span className="signal-copy">
                                <strong>{stage.label}</strong>
                                <small>{stage.state}</small>
                            </span>
                            <span className="signal-node" aria-hidden="true" />
                        </li>
                    ))}
                </ol>

                <section className="queue-panel" aria-labelledby="queue-title">
                    <header className="queue-header">
                        <div>
                            <p className="eyebrow">ACTIVE QUEUE</p>
                            <h2 id="queue-title">事故队列</h2>
                        </div>
                        <span className="queue-count">0 ACTIVE</span>
                    </header>

                    <div className="connection-state" role="status">
                        <span className="radar-mark" aria-hidden="true">
                            <span />
                        </span>
                        <p>正在连接控制平面…</p>
                        <small>新事故将在完成归并与风险分级后显示于此。</small>
                    </div>
                </section>
            </main>
        </div>
    );
}
