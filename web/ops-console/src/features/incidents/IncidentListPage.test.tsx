import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";

import { AuthProvider } from "../../auth/AuthProvider";
import { createCockpit, server } from "../../mocks/handlers";
import { IncidentListPage } from "./IncidentListPage";

it("discovers a new incident after initially showing an empty queue", async () => {
    let requests = 0;
    server.use(
        http.get("*/api/v1/incidents", () => {
            requests += 1;
            return HttpResponse.json({
                items: requests === 1 ? [] : [createCockpit().incident],
                nextCursor: null,
            });
        }),
    );
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    const view = render(
        <QueryClientProvider client={queryClient}>
            <AuthProvider
                user={{
                    subject: "observer-demo",
                    displayName: "Observer",
                    roles: ["OBSERVER"],
                    serviceIds: [],
                }}
            >
                <MemoryRouter>
                    <IncidentListPage />
                </MemoryRouter>
            </AuthProvider>
        </QueryClientProvider>,
    );
    try {
        expect(await screen.findByText("当前没有事故")).toBeVisible();
        expect(
            await screen.findByText("checkout-api", {}, { timeout: 7_000 }),
        ).toBeVisible();
    } finally {
        view.unmount();
        queryClient.clear();
    }
}, 10_000);
