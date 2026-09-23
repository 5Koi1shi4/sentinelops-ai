import { expect, test, type Browser, type Page } from "@playwright/test";

const passwords: Record<string, string> = {
    "observer-demo":
        process.env.SENTINELOPS_OBSERVER_PASSWORD ?? "observer-demo",
    "platform-admin-demo":
        process.env.SENTINELOPS_PLATFORM_ADMIN_PASSWORD ??
        "platform-admin-demo",
};

async function loginAs(browser: Browser, username: string): Promise<Page> {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto("/incidents");
    await page.getByRole("button", { name: "登录控制平面" }).click();
    await page.locator("#username").fill(username);
    await page.locator("#password").fill(passwords[username]);
    await page.locator("#kc-login").click();
    await page.waitForURL(/\/incidents(?:\?.*)?$/);
    return page;
}

test("observer sees published Runbook but cannot open Eval governance", async ({
    browser,
}) => {
    const page = await loginAs(browser, "observer-demo");
    await page.goto("/runbooks");
    await expect(
        page.getByRole("heading", { name: "Runbook 管理" }),
    ).toBeVisible();
    await expect(page.getByText("RB-DB-POOL-03")).toBeVisible();
    await expect(page.getByRole("link", { name: "新建 Runbook" })).toHaveCount(
        0,
    );

    await page.goto("/evals");
    await expect(page.getByRole("alert")).toContainText("需要平台管理员权限");
    await page.context().close();
});

test("platform admin reviews immutable Runbook and passing Eval thresholds", async ({
    browser,
}) => {
    const page = await loginAs(browser, "platform-admin-demo");
    await page.goto("/runbooks");
    await expect(
        page.getByRole("link", { name: "新建 Runbook" }),
    ).toBeVisible();
    await page.getByRole("link", { name: /RB-DB-POOL-03/ }).click();
    await expect(
        page.getByRole("button", { name: "创建下一版草稿" }),
    ).toBeVisible();
    await expect(
        page.getByText("已发布版本保持只读", { exact: false }),
    ).toBeVisible();

    await page.goto("/evals");
    await expect(page.getByRole("heading", { name: "评估治理" })).toBeVisible();
    await page.getByRole("button", { name: "运行当前配置" }).click();
    await expect(page.getByText("服务端发布闸门：允许")).toBeVisible({
        timeout: 90_000,
    });
    await expect(
        page.getByRole("heading", { name: "安全硬阈值" }),
    ).toBeVisible();
    await expect(
        page.getByRole("table", { name: "发布硬阈值 · 缺失数据按未知处理" }),
    ).toContainText("引用可解析率");
    await page.context().close();
});
