import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { AuthProvider } from "../../auth/AuthProvider";
import {
    capturedRequests,
    createCockpit,
    INCIDENT_ID,
} from "../../mocks/handlers";
import { ExecutionCard } from "./ExecutionCard";

it("starts execution only for a server-confirmed approved request", async () => {
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
                    subject: "operator-demo",
                    displayName: "演示值班工程师",
                    roles: ["ON_CALL_OPERATOR"],
                    serviceIds: [cockpit.incident.serviceId],
                }}
            >
                <ExecutionCard
                    incidentId={INCIDENT_ID}
                    incidentVersion={7}
                    proposalId={cockpit.diagnosis!.id}
                    approval={{
                        ...cockpit.activeApproval!,
                        status: "APPROVED",
                    }}
                    execution={null}
                />
            </AuthProvider>
        </QueryClientProvider>,
    );

    await user.click(screen.getByRole("button", { name: "执行已审批方案" }));

    await waitFor(() => expect(capturedRequests.execution).not.toBeNull());
    expect(capturedRequests.execution!.headers.get("If-Match")).toBe('"7"');
    expect(capturedRequests.execution!.headers.get("Idempotency-Key")).toMatch(
        /^execution-/,
    );
});
