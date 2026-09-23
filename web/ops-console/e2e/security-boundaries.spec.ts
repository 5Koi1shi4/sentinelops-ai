import { expect, test } from "@playwright/test";

const oidcOrigin =
    process.env.SENTINELOPS_OIDC_ORIGIN ?? "http://localhost:8081";
const observerPassword =
    process.env.SENTINELOPS_OBSERVER_PASSWORD ?? "observer-demo";

test("serves the console with restrictive browser security headers", async ({
    request,
}) => {
    const response = await request.get("/");
    expect(response.ok()).toBeTruthy();

    const headers = response.headers();
    const csp = headers["content-security-policy"] ?? "";
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("style-src 'self'");
    expect(csp).toContain("img-src 'self' data:");
    expect(csp).toContain(`connect-src 'self' ${oidcOrigin}`);
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("base-uri 'self'");
    expect(csp).toContain(`form-action 'self' ${oidcOrigin}`);
    expect(csp).not.toMatch(/\*|'unsafe-inline'|'unsafe-eval'/);
    expect(headers["x-content-type-options"]).toBe("nosniff");
    expect(headers["referrer-policy"]).toBe("no-referrer");
    expect(headers["permissions-policy"]).toContain("camera=()");
    expect(headers["x-frame-options"]).toBe("DENY");
    expect(headers["strict-transport-security"]).toBeUndefined();
});

test("does not enable HSTS from a client-supplied forwarded-proto header", async ({
    request,
}) => {
    const response = await request.get("/", {
        headers: { "X-Forwarded-Proto": "https" },
    });

    expect(response.headers()["strict-transport-security"]).toBeUndefined();
});

test("uses authorization code with PKCE and keeps OIDC user state in session storage", async ({
    page,
}) => {
    await page.goto("/incidents");
    const consoleOrigin = new URL(page.url()).origin;
    const authorizationRequest = page.waitForRequest((request) =>
        request.url().includes("/protocol/openid-connect/auth"),
    );

    await page.getByRole("button", { name: "登录控制平面" }).click();

    const request = await authorizationRequest;
    const authorization = new URL(request.url());
    expect(authorization.origin).toBe(oidcOrigin);
    expect(authorization.searchParams.get("response_type")).toBe("code");
    expect(authorization.searchParams.get("redirect_uri")).toBe(
        `${consoleOrigin}/auth/callback`,
    );
    expect(authorization.searchParams.get("code_challenge_method")).toBe(
        "S256",
    );
    expect(authorization.searchParams.get("code_challenge")).toMatch(
        /^[A-Za-z0-9_-]{43}$/,
    );

    await page.locator("#username").fill("observer-demo");
    await page.locator("#password").fill(observerPassword);
    await page.locator("#kc-login").click();
    await page.waitForURL(/\/incidents(?:\?.*)?$/);

    const oidcKeys = await page.evaluate(() => ({
        local: Array.from({ length: localStorage.length }, (_, index) =>
            localStorage.key(index),
        ),
        session: Array.from({ length: sessionStorage.length }, (_, index) =>
            sessionStorage.key(index),
        ),
    }));
    expect(oidcKeys.local.some((key) => key?.startsWith("oidc."))).toBe(false);
    expect(oidcKeys.session.some((key) => key?.startsWith("oidc.user"))).toBe(
        true,
    );
});
