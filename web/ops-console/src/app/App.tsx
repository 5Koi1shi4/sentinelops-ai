import { useState } from "react";
import { BrowserRouter, Link, Navigate, Route, Routes } from "react-router-dom";

import { AuthProvider } from "../auth/AuthProvider";
import { useAuth } from "../auth/authContext";
import { IncidentDetailPage } from "../features/incidents/IncidentDetailPage";
import { IncidentListPage } from "../features/incidents/IncidentListPage";

const navigation = [
    { index: "01", label: "事故中心", href: "/incidents" },
    { index: "02", label: "审批工作台", href: "/incidents?view=approvals" },
    { index: "03", label: "Runbooks", href: "/incidents?view=runbooks" },
    { index: "04", label: "AI 评测", href: "/incidents?view=evaluations" },
];

function SignedOutWorkspace() {
    const { signIn } = useAuth();
    const [error, setError] = useState<string | null>(null);
    const startSignIn = async () => {
        try {
            setError(null);
            await signIn();
        } catch (failure) {
            setError(
                failure instanceof Error ? failure.message : "无法启动登录",
            );
        }
    };
    return (
        <section className="incident-intro signed-out-panel">
            <div>
                <p className="eyebrow">
                    INCIDENT COMMAND / AUTHENTICATION REQUIRED
                </p>
                <h1>事故中心</h1>
                <p className="lede">
                    登录后读取你负责服务范围内的事故与审批状态。
                </p>
                <button
                    className="primary-action"
                    type="button"
                    onClick={startSignIn}
                >
                    登录控制平面
                </button>
                <p className="mutation-status" role="status" aria-live="polite">
                    {error}
                </p>
            </div>
        </section>
    );
}

function Console() {
    const { user, isLoading } = useAuth();
    return (
        <div className="console-shell">
            <a className="skip-link" href="#main-content">
                跳至主要内容
            </a>
            <aside className="command-rail">
                <Link
                    className="brand-lockup"
                    to="/incidents"
                    aria-label="SentinelOps 首页"
                >
                    <span className="brand-mark" aria-hidden="true">
                        S
                    </span>
                    <span>
                        <strong>SentinelOps</strong>
                        <small>AI CONTROL PLANE</small>
                    </span>
                </Link>
                <div className="rail-caption">
                    <span>INCIDENT COMMAND</span>
                    <span className="live-indicator">
                        <span className="status-dot" aria-hidden="true" /> LIVE
                    </span>
                </div>
                <nav className="primary-navigation" aria-label="主导航">
                    <ol>
                        {navigation.map((item, index) => (
                            <li key={item.index}>
                                <Link
                                    className={index === 0 ? "is-current" : ""}
                                    to={item.href}
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
                                </Link>
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
            <main id="main-content" className="workspace" tabIndex={-1}>
                <header className="topbar">
                    <p>
                        <span className="status-dot" aria-hidden="true" />
                        控制平面在线
                    </p>
                    <p className="topbar-meta">
                        {user
                            ? `${user.displayName} · ${user.roles.join(" / ")}`
                            : "CN / UTC+08"}
                    </p>
                </header>
                {isLoading ? (
                    <div className="detail-loading" role="status">
                        正在恢复会话…
                    </div>
                ) : user ? (
                    <Routes>
                        <Route
                            path="/incidents"
                            element={<IncidentListPage />}
                        />
                        <Route
                            path="/incidents/:incidentId"
                            element={<IncidentDetailPage />}
                        />
                        <Route
                            path="*"
                            element={<Navigate replace to="/incidents" />}
                        />
                    </Routes>
                ) : (
                    <SignedOutWorkspace />
                )}
            </main>
        </div>
    );
}

export function App() {
    return (
        <AuthProvider>
            <BrowserRouter>
                <Console />
            </BrowserRouter>
        </AuthProvider>
    );
}
