import { defineConfig, devices } from "@playwright/test";

const verificationOutput =
    process.env.SENTINELOPS_VERIFICATION_DIR ?? "../../build/verification";

export default defineConfig({
    testDir: process.env.SENTINELOPS_FAULT_TEST_DIR ?? "./e2e",
    fullyParallel: false,
    workers: 1,
    timeout: 180_000,
    expect: { timeout: 30_000 },
    forbidOnly: Boolean(process.env.CI),
    retries: process.env.CI ? 1 : 0,
    reporter: [
        ["list"],
        [
            "html",
            {
                open: "never",
                outputFolder: `${verificationOutput}/playwright-report`,
            },
        ],
    ],
    outputDir: `${verificationOutput}/playwright-artifacts`,
    use: {
        baseURL: process.env.SENTINELOPS_WEB_URL ?? "http://localhost:4173",
        locale: "zh-CN",
        timezoneId: "Asia/Shanghai",
        trace: "retain-on-failure",
        screenshot: "only-on-failure",
        video: "retain-on-failure",
    },
    projects: [
        {
            name: "chromium",
            use: { ...devices["Desktop Chrome"] },
        },
    ],
});
