import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";

const oidc = vi.hoisted(() => {
    const manager = {
        getUser: vi.fn(async () => null),
        signinRedirect: vi.fn(async () => undefined),
        signinRedirectCallback: vi.fn(async () => null),
        signoutRedirectCallback: vi.fn(async () => undefined),
        signoutRedirect: vi.fn(async () => undefined),
        events: {
            addUserLoaded: vi.fn(),
            addUserUnloaded: vi.fn(),
            removeUserLoaded: vi.fn(),
            removeUserUnloaded: vi.fn(),
        },
    };
    const state: { instance: typeof manager | null; settings: unknown } = {
        instance: null,
        settings: null,
    };

    return { manager, state };
});

vi.mock("oidc-client-ts", () => ({
    UserManager: vi.fn(function (settings: unknown) {
        oidc.state.instance = oidc.manager;
        oidc.state.settings = settings;
        return oidc.manager;
    }),
    WebStorageStateStore: class {
        constructor(options: unknown) {
            void options;
        }
    },
}));

import { AuthProvider } from "./AuthProvider";
import { useAuth, type AuthUser } from "./authContext";

function LogoutButton() {
    const { signOut } = useAuth();
    return (
        <button type="button" onClick={() => void signOut()}>
            Sign out
        </button>
    );
}

const user: AuthUser = {
    subject: "operator-1",
    displayName: "Operator",
    roles: ["ON_CALL_OPERATOR"],
    serviceIds: ["checkout-api"],
    accessToken: "access-token",
};

beforeEach(() => {
    vi.stubEnv(
        "VITE_OIDC_ISSUER",
        "https://identity.example.test/realms/sentinelops",
    );
    vi.stubEnv("VITE_OIDC_CLIENT_ID", "sentinelops-console");
    vi.stubEnv(
        "VITE_OIDC_REDIRECT_URI",
        `${window.location.origin}/auth/callback`,
    );
    oidc.manager.signoutRedirect.mockClear();
    oidc.manager.signoutRedirectCallback.mockClear();
    oidc.manager.getUser.mockResolvedValue(null);
    oidc.state.instance = null;
    oidc.state.settings = null;
});

it("clears cached API data before starting the OIDC logout redirect", async () => {
    const client = new QueryClient();
    client.setQueryData(["private", "incident-list"], { bearer: "cached" });
    let cacheEntriesAtRedirect = -1;
    oidc.manager.signoutRedirect.mockImplementation(async () => {
        cacheEntriesAtRedirect = client.getQueryCache().getAll().length;
    });

    render(
        <QueryClientProvider client={client}>
            <AuthProvider user={user} queryClient={client}>
                <LogoutButton />
            </AuthProvider>
        </QueryClientProvider>,
    );

    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(oidc.manager.signoutRedirect).toHaveBeenCalledOnce();
    expect(cacheEntriesAtRedirect).toBe(0);
    expect(client.getQueryCache().getAll()).toHaveLength(0);
    client.clear();
    vi.unstubAllEnvs();
});

it("consumes the OIDC logout state when the identity provider returns", async () => {
    window.history.replaceState({}, document.title, "/?state=logout-state");

    render(
        <QueryClientProvider client={new QueryClient()}>
            <AuthProvider>
                <p>Signed out</p>
            </AuthProvider>
        </QueryClientProvider>,
    );

    await waitFor(() =>
        expect(oidc.manager.signoutRedirectCallback).toHaveBeenCalledOnce(),
    );
    expect(window.location.pathname).toBe("/incidents");
});
