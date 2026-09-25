import { expect, test, type Browser, type Page } from "@playwright/test";

const keycloakUrl = "http://127.0.0.1:8081";
const demoUrl = "http://127.0.0.1:8082";
const prometheusUrl = "http://127.0.0.1:9090";
const lokiUrl = "http://127.0.0.1:3100";

async function injectDemoFault(): Promise<void> {
    const tokenResponse = await fetch(
        `${keycloakUrl}/realms/sentinelops/protocol/openid-connect/token`,
        {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: new URLSearchParams({
                grant_type: "client_credentials",
                client_id: "sentinelops-demo-controller",
                client_secret:
                    process.env.SENTINELOPS_DEMO_CONTROLLER_CLIENT_SECRET ??
                    "sentinelops-demo-controller-secret",
                scope: "demo:fault",
            }),
        },
    );
    expect(tokenResponse.status).toBe(200);
    const token = (await tokenResponse.json()) as { access_token: string };
    const response = await fetch(
        `${demoUrl}/internal/demo/faults/connection-pool`,
        {
            method: "POST",
            headers: { Authorization: `Bearer ${token.access_token}` },
        },
    );
    expect(response.status).toBe(200);
    expect((await response.json()) as { active: boolean }).toMatchObject({
        active: true,
    });
}

async function waitForEvidence(): Promise<void> {
    for (let batch = 0; batch < 2; batch += 1) {
        for (let index = 0; index < 12; index += 1) {
            const response = await fetch(`${demoUrl}/api/checkout`, {
                method: "POST",
            });
            expect(response.status).toBe(503);
        }
        await new Promise((resolve) => setTimeout(resolve, 2_500));
    }
    await expect
        .poll(
            async () => {
                const metric = await fetch(
                    `${prometheusUrl}/api/v1/query?query=demo_pool_pending`,
                );
                const logs = await fetch(
                    `${lokiUrl}/loki/api/v1/query_range?query=${encodeURIComponent(
                        '{service_name="sentinelops-demo-service"} |= "Demo checkout acquire timeout"',
                    )}`,
                );
                if (!metric.ok || !logs.ok) return false;
                const metricBody = (await metric.json()) as {
                    data?: { result?: Array<{ value?: [number, string] }> };
                };
                const logBody = (await logs.json()) as {
                    data?: {
                        result?: Array<{ values?: Array<[string, string]> }>;
                    };
                };
                return (
                    Number(metricBody.data?.result?.[0]?.value?.[1] ?? 0) > 0 &&
                    (logBody.data?.result?.some(
                        (stream) => (stream.values?.length ?? 0) > 0,
                    ) ??
                        false)
                );
            },
            { timeout: 60_000 },
        )
        .toBe(true);
}

async function login(browser: Browser, username: string): Promise<Page> {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto("/incidents");
    await page.getByRole("button", { name: "登录控制平面" }).click();
    await page.locator("#username").fill(username);
    await page
        .locator("#password")
        .fill(
            process.env[
                `SENTINELOPS_${username.split("-")[0].toUpperCase()}_PASSWORD`
            ] ?? username,
        );
    await page.locator("#kc-login").click();
    await page.waitForURL(/\/incidents(?:\?.*)?$/);
    return page;
}

async function incidentPage(page: Page): Promise<void> {
    const incident = page.locator('a[href^="/incidents/"]').filter({
        hasText: "checkout-api",
        has: page.locator(
            ".state-chip:not(.state-resolved):not(.state-suppressed)",
        ),
    });
    await expect(incident).toHaveCount(1, { timeout: 90_000 });
    await incident.click();
}

test("approved execution stays pending while delivery is unavailable", async ({
    browser,
}) => {
    await injectDemoFault();
    await waitForEvidence();
    const operator = await login(browser, "operator-demo");
    await incidentPage(operator);
    await operator.getByRole("button", { name: "运行证据诊断" }).click();
    await expect(
        operator.getByRole("link", { name: "pool_pending" }).first(),
    ).toBeVisible();
    await expect(
        operator.getByRole("link", { name: "acquire_timeout_logs" }).first(),
    ).toBeVisible();
    await operator.getByRole("button", { name: "提交审批" }).click();
    await expect(operator.getByText("PENDING", { exact: true })).toBeVisible();

    const approver = await login(browser, "approver-demo");
    await incidentPage(approver);
    const approve = approver.getByRole("button", { name: /批准 \d+ 分钟/ });
    await expect(approve).toBeEnabled();
    await approve.click();
    await expect(approver.getByText("服务器确认：APPROVED")).toBeVisible();

    await operator.reload();
    const execute = operator.getByRole("button", { name: "执行已审批方案" });
    await expect(execute).toBeEnabled();
    await execute.click();
    await expect(operator.locator(".execution-card .state-chip")).toHaveText(
        "PENDING",
    );
    const incidentId = new URL(operator.url()).pathname.split("/").at(-1);
    expect(incidentId).toMatch(/^[0-9a-f-]{36}$/);
    console.log(`TASK7_INCIDENT_ID=${incidentId}`);
    await operator.context().close();
    await approver.context().close();
});
