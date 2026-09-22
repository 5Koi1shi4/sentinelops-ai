import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { AuthProvider, type AuthUser } from "../auth/AuthProvider";
import { server } from "../mocks/handlers";
import { Console } from "./App";

it("preserves unsaved draft input on token renewal but clears it on identity change", async () => {
    server.use(http.get("/api/v1/services", () => HttpResponse.json([])));
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    const user: AuthUser = {
        subject: "author-a",
        displayName: "Author",
        roles: ["PLATFORM_ADMIN"],
        serviceIds: [],
        accessToken: "token-before-renewal",
    };
    const tree = (identity: AuthUser) => (
        <QueryClientProvider client={client}>
            <AuthProvider user={identity}>
                <MemoryRouter initialEntries={["/runbooks/new"]}>
                    <Console />
                </MemoryRouter>
            </AuthProvider>
        </QueryClientProvider>
    );
    const view = render(tree(user));
    await userEvent.type(
        await screen.findByRole("textbox", { name: "Runbook Key" }),
        "RB-UNSAVED",
    );
    view.rerender(tree({ ...user, accessToken: "token-after-renewal" }));
    await waitFor(() =>
        expect(
            screen.getByRole("textbox", { name: "Runbook Key" }),
        ).toHaveValue("RB-UNSAVED"),
    );
    view.rerender(
        tree({ ...user, subject: "author-b", accessToken: "other-session" }),
    );
    await waitFor(() =>
        expect(
            screen.getByRole("textbox", { name: "Runbook Key" }),
        ).toHaveValue(""),
    );
});
