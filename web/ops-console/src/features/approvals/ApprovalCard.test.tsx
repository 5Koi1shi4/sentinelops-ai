import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { AuthProvider } from "../../auth/AuthProvider";
import {
    capturedRequests,
    createCockpit,
    INCIDENT_ID,
} from "../../mocks/handlers";
import { ApprovalCard } from "./ApprovalCard";

it("sends decision with idempotency key and visible resource version", async () => {
    const user = userEvent.setup();
    const cockpit = createCockpit();
    const queryClient = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
        },
    });

    render(
        <QueryClientProvider client={queryClient}>
            <AuthProvider
                user={{
                    subject: "approver-demo",
                    displayName: "演示 SRE 审批人",
                    roles: ["SRE_APPROVER"],
                    serviceIds: [cockpit.incident.serviceId],
                }}
            >
                <ApprovalCard
                    incidentId={INCIDENT_ID}
                    incidentVersion={7}
                    diagnosis={cockpit.diagnosis!}
                    approval={{
                        ...cockpit.activeApproval!,
                        requesterSubject: "another-operator",
                    }}
                />
            </AuthProvider>
        </QueryClientProvider>,
    );

    await user.click(
        await screen.findByRole("button", { name: "批准 8 分钟" }),
    );

    await waitFor(() =>
        expect(capturedRequests.approvalDecision).not.toBeNull(),
    );
    expect(
        capturedRequests.approvalDecision!.headers.get("Idempotency-Key"),
    ).toMatch(/^approval-/);
    expect(capturedRequests.approvalDecision!.headers.get("If-Match")).toBe(
        '"7"',
    );
});
