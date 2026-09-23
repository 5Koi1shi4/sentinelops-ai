import { beforeEach, expect, it } from "vitest";

import { createOidcUserManagerSettings } from "./authConfig";

const authority = "https://identity.example.test/realms/sentinelops";
const origin = "https://console.example.test";

beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
});

it("uses exact same-origin callback URLs and keeps OIDC state in session storage", async () => {
    const settings = createOidcUserManagerSettings(
        {
            authority,
            clientId: "sentinelops-console",
            redirectUri: `${origin}/auth/callback`,
        },
        { origin, sessionStorage: window.sessionStorage },
    );

    expect(settings).toMatchObject({
        authority,
        client_id: "sentinelops-console",
        redirect_uri: `${origin}/auth/callback`,
        post_logout_redirect_uri: `${origin}/`,
        response_type: "code",
    });

    await settings?.userStore?.set("session-user", "user-token");
    await settings?.stateStore?.set("login-state", "state-token");

    expect(window.sessionStorage.length).toBe(2);
    expect(window.localStorage.length).toBe(0);
});

it.each([
    "https://attacker.example.test/auth/callback",
    `${origin}/auth/callback?next=https://attacker.example.test`,
    `${origin}/auth/callback#fragment`,
])(
    "rejects a redirect URI that is not the exact callback URL: %s",
    (redirectUri) => {
        expect(() =>
            createOidcUserManagerSettings(
                { authority, clientId: "sentinelops-console", redirectUri },
                { origin, sessionStorage: window.sessionStorage },
            ),
        ).toThrow();
    },
);
