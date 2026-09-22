import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { AuthProvider, type AuthUser } from "../../auth/AuthProvider";
import {
    capturedRequests,
    createCockpit,
    EVIDENCE_ID,
    INCIDENT_ID,
    setMockCockpit,
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
