import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import path from "node:path";

import { expect, test, type Browser, type Page } from "@playwright/test";

const execFileAsync = promisify(execFile);
const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url));
const composeFiles = [
    "compose",
    "-p",
    "sentinelops",
    "-f",
    path.join(repositoryRoot, "deploy/compose/compose.core.yml"),
    "-f",
    path.join(repositoryRoot, "deploy/compose/compose.demo.yml"),
];
const keycloakBaseUrl =
    process.env.SENTINELOPS_KEYCLOAK_URL ?? "http://localhost:8081";
const prometheusBaseUrl =
    process.env.SENTINELOPS_PROMETHEUS_URL ?? "http://localhost:9090";
const demoControllerSecret =
    process.env.SENTINELOPS_DEMO_CONTROLLER_CLIENT_SECRET ??
    "sentinelops-demo-controller-secret";

const passwords: Record<string, string> = {
    "observer-demo":
        process.env.SENTINELOPS_OBSERVER_PASSWORD ?? "observer-demo",
    "operator-demo":
        process.env.SENTINELOPS_OPERATOR_PASSWORD ?? "operator-demo",
    "approver-demo":
        process.env.SENTINELOPS_APPROVER_PASSWORD ?? "approver-demo",
};

async function demoControllerToken(): Promise<string> {
    const body = new URLSearchParams({
        grant_type: "client_credentials",
        client_id: "sentinelops-demo-controller",
        client_secret: demoControllerSecret,
        scope: "demo:fault",
    });
    const response = await fetch(
        `${keycloakBaseUrl}/realms/sentinelops/protocol/openid-connect/token`,
        {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body,
        },
    );
    if (!response.ok) {
        throw new Error(
            `Demo controller token request failed: ${response.status} ${await response.text()}`,
        );
    }
    const token = (await response.json()) as { access_token?: string };
    expect(token.access_token).toBeTruthy();
    return token.access_token as string;
}

async function injectDemoFault(): Promise<void> {
    const token = await demoControllerToken();
    const { stdout } = await execFileAsync(
        "docker",
        [
            ...composeFiles,
            "exec",
            "-T",
            "demo-service",
            "wget",
            "-qO-",
            "--header",
            `Authorization: Bearer ${token}`,
            "--post-data=",
            "http://127.0.0.1:8082/internal/demo/faults/connection-pool",
        ],
        { cwd: repositoryRoot },
    );
    expect(JSON.parse(stdout) as { active: boolean }).toMatchObject({
        active: true,
    });
}

async function loginAs(browser: Browser, username: string): Promise<Page> {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto("/incidents");
    await page.getByRole("button", { name: "登录控制平面" }).click();
    await page.locator("#username").fill(username);
    const password = passwords[username];
    if (!password) {
        throw new Error(`No Demo password is configured for ${username}`);
    }
    await page.locator("#password").fill(password);
    await page.locator("#kc-login").click();
    await page.waitForURL(/\/incidents(?:\?.*)?$/);
    return page;
}

async function waitForIncident(page: Page, serviceKey: string): Promise<Page> {
    const incidents = page
        .locator('a[href^="/incidents/"]')
        .filter({ hasText: serviceKey });
    await expect(incidents).toHaveCount(1, { timeout: 90_000 });
    await incidents.click();
    await expect(page.getByText(serviceKey).first()).toBeVisible();
    return page;
}

async function runDiagnosisAndSubmitApproval(page: Page): Promise<void> {
    await page.getByRole("button", { name: "运行证据诊断" }).click();
    await expect(page.getByRole("link", { name: "E-12" })).toBeVisible({
        timeout: 30_000,
    });
    await page.getByRole("button", { name: "提交审批" }).click();
    await expect(page.getByText("PENDING", { exact: true })).toBeVisible();
}

async function approvePendingR1(page: Page): Promise<void> {
    const approve = page.getByRole("button", { name: /批准 \d+ 分钟/ });
    await expect(approve).toBeEnabled();
    await approve.click();
    await expect(page.getByText("服务器确认：APPROVED")).toBeVisible();
}

async function startApprovedExecution(page: Page): Promise<void> {
    const execute = page.getByRole("button", { name: "执行已审批方案" });
    await expect(execute).toBeEnabled({ timeout: 30_000 });
    await execute.click();
    await expect(page.locator(".execution-card .state-chip")).toHaveText(
        /PENDING|RUNNING|VERIFYING|SUCCEEDED/,
        { timeout: 30_000 },
    );
}

async function expectResolvedWithVerification(page: Page): Promise<void> {
    await expect
        .poll(
            async () => {
                await page.reload();
                return page
                    .locator(".incident-state-lockup strong")
                    .textContent();
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
        )
        .toBe("RESOLVED");
    await expect(
        page.getByText(/Recovery verification succeeded/i),
    ).toBeVisible();
}

async function demoRecoverySideEffectCount(): Promise<number> {
    const query = encodeURIComponent("demo_recovery_side_effect_total");
    const response = await fetch(
        `${prometheusBaseUrl}/api/v1/query?query=${query}`,
    );
    expect(response.ok).toBeTruthy();
    const payload = (await response.json()) as {
        data?: { result?: Array<{ value?: [number, string] }> };
    };
    return Number(payload.data?.result?.[0]?.value?.[1] ?? 0);
}

test("detects, diagnoses, approves, executes, and verifies one incident", async ({
    browser,
}) => {
    await injectDemoFault();

    const operator = await loginAs(browser, "operator-demo");
    const incident = await waitForIncident(operator, "checkout-api");
    await expect(incident.getByText("数据库连接池耗尽")).toBeVisible();
    await runDiagnosisAndSubmitApproval(incident);
    await expect(
        incident.getByRole("button", { name: /批准 \d+ 分钟/ }),
    ).toBeDisabled();
    await expect(
        incident.getByText("请求者不能审批自己的 R1 变更"),
    ).toBeVisible();

    const approver = await loginAs(browser, "approver-demo");
    const pending = await waitForIncident(approver, "checkout-api");
    await approvePendingR1(pending);

    await incident.reload();
    await startApprovedExecution(incident);
    await expectResolvedWithVerification(incident);
    await expect.poll(demoRecoverySideEffectCount, { timeout: 30_000 }).toBe(1);

    await operator.context().close();
    await approver.context().close();
});
