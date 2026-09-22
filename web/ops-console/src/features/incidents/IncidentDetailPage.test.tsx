import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { http, HttpResponse } from "msw";

import { AuthProvider, type AuthUser } from "../../auth/AuthProvider";
import {
    capturedRequests,
    createCockpit,
    EVIDENCE_ID,
    INCIDENT_ID,
    setMockCockpit,
    server,
} from "../../mocks/handlers";
import { IncidentDetailPage } from "./IncidentDetailPage";

const operatorWhoRequestedAction: AuthUser = {
    subject: "operator-demo",
    displayName: "演示值班工程师",
    roles: ["ON_CALL_OPERATOR"],
    serviceIds: ["0199a90a-9c00-7000-8000-000000000010"],
};

function renderIncidentDetail(user: AuthUser = operatorWhoRequestedAction) {
    const queryClient = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
        },
    });
    return render(
        <QueryClientProvider client={queryClient}>
            <AuthProvider user={user}>
                <MemoryRouter>
                    <IncidentDetailPage incidentId={INCIDENT_ID} />
                </MemoryRouter>
            </AuthProvider>
        </QueryClientProvider>,
    );
}

it("shows evidence beside the diagnosis and blocks self approval", async () => {
    setMockCockpit(createCockpit());
    renderIncidentDetail();

    expect(await screen.findByText("数据库连接池耗尽")).toBeVisible();
    expect(screen.getByRole("link", { name: "E-12" })).toHaveAttribute(
        "href",
        `#evidence-${EVIDENCE_ID}`,
    );
    expect(screen.getByRole("button", { name: "批准 8 分钟" })).toBeDisabled();
    expect(screen.getByText("请求者不能审批自己的 R1 变更")).toBeVisible();
});

it("hides the previous cockpit immediately when the same user's service grant changes", async () => {
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    const identity = {
        ...operatorWhoRequestedAction,
        accessToken: "old-grant",
    };
    const tree = (user: AuthUser) => (
        <QueryClientProvider client={client}>
            <AuthProvider user={user}>
                <MemoryRouter>
                    <IncidentDetailPage incidentId={INCIDENT_ID} />
                </MemoryRouter>
            </AuthProvider>
        </QueryClientProvider>
    );
    server.use(
        http.get(`/api/v1/incidents/${INCIDENT_ID}`, ({ request }) =>
            request.headers.get("Authorization") === "Bearer old-grant"
                ? HttpResponse.json(createCockpit())
                : HttpResponse.json({ title: "Forbidden" }, { status: 403 }),
        ),
    );
    const view = render(tree(identity));
    expect(await screen.findByText("数据库连接池耗尽")).toBeVisible();
    view.rerender(
        tree({ ...identity, accessToken: "new-grant", serviceIds: [] }),
    );
    expect(screen.queryByText("数据库连接池耗尽")).not.toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("无法读取事故");
});

it("requests approval and starts execution only from server-confirmed states", async () => {
    const user = userEvent.setup();
    setMockCockpit(
        createCockpit({
            incident: {
                ...createCockpit().incident,
                status: "DIAGNOSED",
            },
            activeApproval: null,
        }),
    );
    const view = renderIncidentDetail();

    await user.click(await screen.findByRole("button", { name: "提交审批" }));
    await waitFor(() =>
        expect(capturedRequests.approvalRequest).not.toBeNull(),
    );

    view.unmount();
    const approved = createCockpit();
    setMockCockpit({
        ...approved,
        activeApproval: { ...approved.activeApproval!, status: "APPROVED" },
    });
    renderIncidentDetail();

    await user.click(
        await screen.findByRole("button", { name: "执行已审批方案" }),
    );
    await waitFor(() => expect(capturedRequests.execution).not.toBeNull());
});
