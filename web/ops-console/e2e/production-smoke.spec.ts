import { createHmac, randomBytes, randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

function required(name: string): string {
    const value = process.env[name]?.trim();
    if (!value) throw new Error(`${name} is required for production smoke`);
    return value;
}

const baseUrl = required("SENTINELOPS_WEB_URL");
const oidcIssuer = required("SENTINELOPS_PROD_OIDC_ISSUER");
const operatorToken = required("SENTINELOPS_PROD_OPERATOR_TOKEN");
const observerToken = required("SENTINELOPS_PROD_OBSERVER_TOKEN");
const webhookSource = required("SENTINELOPS_PROD_WEBHOOK_SOURCE");
const webhookSecret = required("SENTINELOPS_PROD_WEBHOOK_SECRET");
const serviceKey = required("SENTINELOPS_PROD_SERVICE_KEY");

test.use({ trace: "off", screenshot: "off", video: "off" });

function endpoint(path: string): URL {
    return new URL(path, baseUrl);
}

if (new URL(baseUrl).protocol !== "https:") {
    throw new Error("Production smoke requires an HTTPS console URL");
}
if (new URL(oidcIssuer).protocol !== "https:") {
    throw new Error("Production smoke requires an HTTPS OIDC issuer");
}

test("only health is public and HTTPS enables HSTS", async () => {
    const health = await fetch(endpoint("/healthz"));
    expect(health.status).toBe(200);
    expect((await health.json()) as { status?: string }).toMatchObject({
        status: "UP",
    });
    expect(health.headers.get("strict-transport-security")).toMatch(
        /max-age=\d+/,
    );

    const incidents = await fetch(endpoint("/api/v1/incidents"));
    expect(incidents.status).toBe(401);

    const forbiddenDemoRoutes: Array<["post" | "delete", string]> = [
        ["post", "/internal/demo/faults/connection-pool"],
        ["delete", "/internal/demo/faults"],
        ["post", "/internal/demo/alertmanager-relay"],
        ["post", "/internal/runbooks/recover-connection-pool"],
        ["post", "/api/checkout"],
    ];
    for (const [method, path] of forbiddenDemoRoutes) {
        const response = await fetch(endpoint(path), {
            method: method.toUpperCase(),
        });
        expect(response.status, `${method.toUpperCase()} ${path}`).toBe(404);
    }
});

test("OIDC login uses the configured issuer and PKCE", async ({ page }) => {
    await page.goto("/incidents");
    const authorizationRequest = page.waitForRequest((request) =>
        request.url().includes("/protocol/openid-connect/auth"),
    );
    await page.getByRole("button", { name: "登录控制平面" }).click();
    const url = new URL((await authorizationRequest).url());
    expect(`${url.origin}${url.pathname}`).toContain(oidcIssuer);
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("code_challenge")).toMatch(
        /^[A-Za-z0-9_-]{43}$/,
    );
    expect(url.searchParams.get("redirect_uri")).toBe(
        `${new URL(baseUrl).origin}/auth/callback`,
    );
});

test("signed alert enters an authorized manual or read-only diagnosis without Demo execution", async () => {
    const fingerprint = `production-smoke-${randomUUID()}`;
    const eventId = randomUUID();
    const labels = {
        alertname: "SentinelOpsProductionSmoke",
        service_key: serviceKey,
        severity: "sev2",
        incident_fingerprint: fingerprint,
    };
    const payload = JSON.stringify({
        version: "4",
        groupKey: fingerprint,
        status: "firing",
        receiver: "sentinelops-production-smoke",
        groupLabels: { alertname: labels.alertname },
        commonLabels: labels,
        commonAnnotations: { summary: "Synthetic production smoke alert" },
        externalURL: `${new URL(baseUrl).origin}/alerts`,
        alerts: [
            {
                status: "firing",
                labels,
                annotations: { summary: "Synthetic production smoke alert" },
                startsAt: new Date().toISOString(),
                endsAt: "0001-01-01T00:00:00Z",
                generatorURL: `${new URL(baseUrl).origin}/smoke`,
                fingerprint: eventId,
            },
        ],
    });
    const timestamp = `${Math.floor(Date.now() / 1000)}`;
    const nonce = randomBytes(16).toString("hex");
    const signature = createHmac("sha256", webhookSecret)
        .update(`${timestamp}\n${nonce}\n${payload}`)
        .digest("hex");
    const alert = await fetch(
        endpoint("/api/v1/integrations/alertmanager/webhook"),
        {
            method: "POST",
            body: payload,
            headers: {
                "Content-Type": "application/json",
                "X-Sentinel-Source": webhookSource,
                "X-Sentinel-Timestamp": timestamp,
                "X-Sentinel-Nonce": nonce,
                "X-Sentinel-Signature": `v1=${signature}`,
                "X-SentinelOps-Event-Id": eventId,
            },
        },
    );
    expect(alert.status).toBe(202);
    const location = alert.headers.get("location");
    expect(location).toMatch(/^\/api\/v1\/incidents\/[0-9a-f-]{36}$/);
    if (!location)
        throw new Error("Signed webhook did not return an incident location");

    const operatorHeaders = { Authorization: `Bearer ${operatorToken}` };
    const list = await fetch(endpoint("/api/v1/incidents"), {
        headers: operatorHeaders,
    });
    expect(list.status).toBe(200);
    const page = (await list.json()) as { items?: Array<{ id?: string }> };
    expect(Array.isArray(page.items)).toBe(true);

    const incident = await fetch(endpoint(location), {
        headers: operatorHeaders,
    });
    expect(incident.status).toBe(200);
    const cockpit = (await incident.json()) as {
        incident?: { id?: string; serviceKey?: string; status?: string };
        latestExecution?: unknown;
    };
    expect(cockpit.incident?.serviceKey).toBe(serviceKey);
    expect(cockpit.latestExecution).toBeNull();

    const denied = await fetch(
        endpoint(`/api/v1/approval-requests/${randomUUID()}/decisions`),
        {
            method: "POST",
            headers: {
                Authorization: `Bearer ${observerToken}`,
                "If-Match": '"1"',
                "Idempotency-Key": randomUUID(),
                "Content-Type": "application/json",
            },
            body: JSON.stringify({ decision: "APPROVE", comment: "smoke" }),
        },
    );
    expect(denied.status).toBe(403);

    const diagnosis = await fetch(endpoint(`${location}/diagnosis-runs`), {
        method: "POST",
        headers: {
            ...operatorHeaders,
            "If-Match": incident.headers.get("etag") ?? "",
            "Idempotency-Key": randomUUID(),
        },
    });
    if (diagnosis.status === 201) {
        const proposal = (await diagnosis.json()) as { id?: string };
        expect(proposal.id).toMatch(/^[0-9a-f-]{36}$/);
    } else {
        expect(diagnosis.status).toBe(503);
        const problem = (await diagnosis.json()) as { errorCode?: string };
        expect(problem.errorCode).toMatch(/^[A-Z][A-Z0-9_]+$/);
    }

    const finalIncident = await fetch(endpoint(location), {
        headers: operatorHeaders,
    });
    expect(finalIncident.status).toBe(200);
    const finalCockpit = (await finalIncident.json()) as {
        incident?: { status?: string };
        latestExecution?: unknown;
    };
    expect(finalCockpit.latestExecution).toBeNull();
    expect(finalCockpit.incident?.status?.toLowerCase()).not.toBe("resolved");
});
