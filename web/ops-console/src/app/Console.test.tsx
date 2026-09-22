import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AuthContext, type AuthUser } from "../auth/authContext";
import { Console } from "./App";

function renderNavigation(path: string, roles: AuthUser["roles"]) {
    render(
        <AuthContext.Provider
            value={{
                user: {
                    subject: "viewer",
                    displayName: "Viewer",
                    roles,
                    serviceIds: [],
                },
                isLoading: true,
                signIn: async () => {},
                signOut: async () => {},
            }}
        >
            <MemoryRouter initialEntries={[path]}>
                <Console />
            </MemoryRouter>
        </AuthContext.Provider>,
    );
}

it("links to governance routes and marks the current section for nested Runbook URLs", () => {
    renderNavigation("/runbooks/checkout/versions/version-a", [
        "PLATFORM_ADMIN",
    ]);
    expect(screen.getByRole("link", { name: "Runbooks" })).toHaveAttribute(
        "href",
        "/runbooks",
    );
    expect(screen.getByRole("link", { name: "Runbooks" })).toHaveAttribute(
        "aria-current",
        "page",
    );
    expect(screen.getByRole("link", { name: "AI 评测" })).toHaveAttribute(
        "href",
        "/evals",
    );
    expect(screen.getByRole("link", { name: "事故中心" })).not.toHaveAttribute(
        "aria-current",
    );
});

it("does not advertise administrator-only Eval reports to observers", () => {
    renderNavigation("/incidents", ["OBSERVER"]);
    expect(
        screen.queryByRole("link", { name: "AI 评测" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Runbooks" })).toBeVisible();
});

it("marks only the approvals navigation entry when the approvals view is active", () => {
    renderNavigation("/incidents?view=approvals", ["SRE_APPROVER"]);
    expect(screen.getByRole("link", { name: "审批工作台" })).toHaveAttribute(
        "aria-current",
        "page",
    );
    expect(screen.getByRole("link", { name: "事故中心" })).not.toHaveAttribute(
        "aria-current",
    );
});
