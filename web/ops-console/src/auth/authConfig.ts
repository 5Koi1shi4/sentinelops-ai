import { WebStorageStateStore, type UserManagerSettings } from "oidc-client-ts";

export interface OidcEnvironment {
    authority?: string;
    clientId?: string;
    redirectUri?: string;
}

export interface OidcBrowserStorage {
    origin: string;
    sessionStorage: Storage;
}

export function createOidcUserManagerSettings(
    environment: OidcEnvironment,
    browser: OidcBrowserStorage,
): UserManagerSettings | null {
    const authority = environment.authority?.trim();
    const clientId = environment.clientId?.trim();
    if (!authority && !clientId) {
        return null;
    }
    if (!authority || !clientId) {
        throw new Error("OIDC issuer and client ID must both be configured");
    }

    const issuer = new URL(authority);
    if (
        !["http:", "https:"].includes(issuer.protocol) ||
        issuer.username ||
        issuer.password ||
        issuer.search ||
        issuer.hash
    ) {
        throw new Error("OIDC issuer must be an HTTP(S) issuer URL");
    }

    const origin = new URL(browser.origin).origin;
    if (origin !== browser.origin) {
        throw new Error(
            "Console origin must not include a path or trailing slash",
        );
    }
    const configuredRedirect =
        environment.redirectUri?.trim() || `${origin}/auth/callback`;
    const redirect = new URL(configuredRedirect);
    if (
        redirect.origin !== origin ||
        redirect.pathname !== "/auth/callback" ||
        redirect.search ||
        redirect.hash ||
        redirect.href !== configuredRedirect
    ) {
        throw new Error(
            "OIDC redirect URI must exactly match the console /auth/callback URL",
        );
    }

    return {
        authority,
        client_id: clientId,
        redirect_uri: configuredRedirect,
        post_logout_redirect_uri: `${origin}/`,
        response_type: "code",
        scope: "openid profile email",
        userStore: new WebStorageStateStore({ store: browser.sessionStorage }),
        stateStore: new WebStorageStateStore({ store: browser.sessionStorage }),
        automaticSilentRenew: true,
        monitorSession: false,
    };
}
