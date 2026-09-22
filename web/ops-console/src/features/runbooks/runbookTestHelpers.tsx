import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import type { components } from "../../api/generated";
import { AuthProvider, type AuthUser } from "../../auth/AuthProvider";
import { server } from "../../mocks/handlers";
import { RunbookEditorPage } from "./RunbookEditorPage";
import { RunbookListPage } from "./RunbookListPage";

export const SERVICE_ID = "0199a90a-9c00-7000-8000-000000000010";
export const RUNBOOK_ID = "0199a90a-9c00-7000-8000-000000000030";
export const DRAFT_ID = "0199a90a-9c00-7000-8000-000000000031";
export const PUBLISHED_ID = "0199a90a-9c00-7000-8000-000000000032";
export const AUTHOR_ID = "0199a90a-9c00-7000-8000-000000000040";
export const REVIEWER_ID = "0199a90a-9c00-7000-8000-000000000041";
export const RUNBOOK_KEY = "RB-DB-POOL-03";

export const runbookAdmin: AuthUser = {
    subject: AUTHOR_ID,
    displayName: "运行手册管理员",
    roles: ["RUNBOOK_ADMIN"],
    serviceIds: [SERVICE_ID],
    accessToken: "runbook-admin-token",
};

export const independentAdmin: AuthUser = {
    subject: REVIEWER_ID,
    displayName: "独立评审员",
    roles: ["RUNBOOK_ADMIN"],
    serviceIds: [SERVICE_ID],
    accessToken: "independent-admin-token",
};

export const observer: AuthUser = {
    subject: "observer-demo",
    displayName: "值班观察员",
    roles: ["OBSERVER"],
    serviceIds: [SERVICE_ID],
    accessToken: "observer-token",
};

export const runbookDefinition: components["schemas"]["RunbookDefinition"] = {
    runbookKey: RUNBOOK_KEY,
    risk: "R1",
    adapterId: "demo-http",
    parameters: {
        type: "object",
        properties: { replicas: { type: "integer", minimum: 1, maximum: 1 } },
        required: ["replicas"],
        additionalProperties: false,
    },
    steps: [{ stepId: "recover-one", operation: "recover_connection_pool" }],
    verification: {
        probe: "demo_checkout_health",
        successThreshold: 1,
        attempts: 3,
        intervalSeconds: 2,
    },
    rollback: null,
};

export const serviceRows = [
    {
        id: SERVICE_ID,
        serviceKey: "checkout-api",
        displayName: "结账 API",
        ownerTeam: "支付平台组",
    },
];

export function makeVersion(overrides: Record<string, unknown> = {}) {
    return {
        id: DRAFT_ID,
        runbookId: RUNBOOK_ID,
        runbookKey: RUNBOOK_KEY,
        serviceId: SERVICE_ID,
        displayName: "恢复 Checkout 数据库连接池",
        ownerTeam: "支付平台组",
        versionNumber: 2,
        lifecycle: "draft",
        revision: 7,
        definition: runbookDefinition,
        markdown: "先检查连接池等待情况，再执行恢复并验证健康状态。",
        definitionChecksum: "sha256:draft-definition",
        authorPrincipalId: AUTHOR_ID,
        reviewerPrincipalId: null,
        publishedAt: null,
        canReview: false,
        ...overrides,
    };
}

export function makePublishedVersion(overrides: Record<string, unknown> = {}) {
    return makeVersion({
        id: PUBLISHED_ID,
        versionNumber: 1,
        lifecycle: "published",
        revision: 4,
        reviewerPrincipalId: REVIEWER_ID,
        publishedAt: "2026-09-20T10:00:00Z",
        canReview: false,
        ...overrides,
    });
}

export function mockEditorApi({
    current = makeVersion(),
    versions = [makePublishedVersion(), current],
}: {
    current?: ReturnType<typeof makeVersion>;
    versions?: ReturnType<typeof makeVersion>[];
} = {}) {
    const versionsById = new Map(
        versions.map((version) => [version.id, version]),
    );
    server.use(
        http.get("*/api/v1/services", () => HttpResponse.json(serviceRows)),
        http.get("*/api/v1/runbooks/:key/versions", () =>
            HttpResponse.json(versions),
        ),
        http.get("*/api/v1/runbook-versions/:id", ({ params }) => {
            const version = versionsById.get(String(params.id)) ?? current;
            return HttpResponse.json(version, {
                headers: { ETag: `"${version.revision}"` },
            });
        }),
        http.get(
            "*/api/v1/runbook-versions/:id/diff",
            ({ request, params }) => {
                const url = new URL(request.url);
                const before = versionsById.get(String(params.id)) ?? current;
                const after =
                    versionsById.get(
                        url.searchParams.get("otherVersionId") ?? "",
                    ) ?? current;
                return HttpResponse.json({
                    fromVersionId: before.id,
                    toVersionId: after.id,
                    definitionChanged:
                        JSON.stringify(before.definition) !==
                        JSON.stringify(after.definition),
                    markdownChanged: before.markdown !== after.markdown,
                    beforeDefinition: before.definition,
                    afterDefinition: after.definition,
                    beforeMarkdown: before.markdown,
                    afterMarkdown: after.markdown,
                });
            },
        ),
    );
}

export function renderEditor(
    user: AuthUser = runbookAdmin,
    initialPath = `/runbooks/${RUNBOOK_KEY}/versions/${DRAFT_ID}`,
    routeElement: ReactNode = <RunbookEditorPage />,
) {
    const queryClient = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
        },
    });
    const view = render(
        <QueryClientProvider client={queryClient}>
            <AuthProvider user={user}>
                <MemoryRouter initialEntries={[initialPath]}>
                    <Routes>
                        <Route
                            path="/runbooks/new"
                            element={routeElement}
                        />
                        <Route
                            path="/runbooks/:key/versions/:id"
                            element={routeElement}
                        />
                    </Routes>
                </MemoryRouter>
            </AuthProvider>
        </QueryClientProvider>,
    );
    return { ...view, queryClient };
}

export function renderRunbookList(user: AuthUser = runbookAdmin) {
    const queryClient = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
        },
    });
    const view = render(
        <QueryClientProvider client={queryClient}>
            <AuthProvider user={user}>
                <MemoryRouter>
                    <RunbookListPage />
                </MemoryRouter>
            </AuthProvider>
        </QueryClientProvider>,
    );
    return { ...view, queryClient };
}
