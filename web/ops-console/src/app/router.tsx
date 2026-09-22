import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router-dom";

import { useAuth } from "../auth/authContext";
import { IncidentDetailPage } from "../features/incidents/IncidentDetailPage";
import { IncidentListPage } from "../features/incidents/IncidentListPage";

const RunbookListPage = lazy(() =>
    import("../features/runbooks/RunbookListPage").then((module) => ({
        default: module.RunbookListPage,
    })),
);
const RunbookEditorPage = lazy(() =>
    import("../features/runbooks/RunbookEditorPage").then((module) => ({
        default: module.RunbookEditorPage,
    })),
);
const EvalDashboardPage = lazy(() =>
    import("../features/evals/EvalDashboardPage").then((module) => ({
        default: module.EvalDashboardPage,
    })),
);

export function WorkspaceRoutes() {
    const { user } = useAuth();
    return (
        <Suspense
            fallback={
                <p className="detail-loading" role="status">
                    正在装载工作区…
                </p>
            }
        >
            <Routes>
                <Route path="/incidents" element={<IncidentListPage />} />
                <Route
                    path="/incidents/:incidentId"
                    element={<IncidentDetailPage />}
                />
                <Route path="/runbooks" element={<RunbookListPage />} />
                <Route path="/runbooks/new" element={<RunbookEditorPage />} />
                <Route
                    path="/runbooks/:key/versions/:id"
                    element={<RunbookEditorPage />}
                />
                <Route
                    path="/evals"
                    element={
                        user?.roles.includes("PLATFORM_ADMIN") ? (
                            <EvalDashboardPage />
                        ) : (
                            <p role="alert">
                                无权访问 AI 评测，需要平台管理员权限。
                            </p>
                        )
                    }
                />
                <Route
                    path="*"
                    element={<Navigate replace to="/incidents" />}
                />
            </Routes>
        </Suspense>
    );
}
