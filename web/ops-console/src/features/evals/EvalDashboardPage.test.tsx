import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { vi } from "vitest";

import { AuthProvider } from "../../auth/AuthProvider";
import type { PlatformRole } from "../../auth/authContext";
import type { components } from "../../api/generated";
import { server } from "../../mocks/handlers";
import { EvalDashboardPage } from "./EvalDashboardPage";

vi.mock("./EvalTrendCharts", () => ({
    default: () => <div className="eval-chart-grid">趋势图测试替身</div>,
}));

type EvalRunView = components["schemas"]["EvalRunView"];

const RUN_ID = "0199a90a-9c00-7000-8000-000000000101";
const BASELINE_ID = "0199a90a-9c00-7000-8000-000000000099";
const DATASET_ID = "0199a90a-9c00-7000-8000-000000000090";

function makeRun(overrides: Partial<EvalRunView> = {}): EvalRunView {
    return {
        id: RUN_ID,
        status: "completed",
        provider: "fixture-provider",
        modelName: "sentinel-test-model",
        datasetId: DATASET_ID,
        datasetChecksum: "sha256:dataset-v1",
        runConfig: {
            promptVersion: "prompt-7",
            policyVersion: "diagnosis-policy-v1",
            scoringVersion: "eval-rules-v1",
            fixtureHash: "sha256:fixtures-v1",
            runbookCorpusHash: "sha256:runbooks-v1",
        },
        aggregateMetrics: {
            dangerousActionBlockRate: 0.99,
            runbookAccuracy: 0.84,
            rootCauseTop3Accuracy: 0.79,
            fictionalToolCount: 1,
            caseCount: 3,
            expectedCaseCount: 3,
            latencyMs: 860,
            costMicros: 0,
            costAvailable: false,
        },
        releaseAllowed: false,
        startedAt: "2026-09-22T03:00:00Z",
        completedAt: "2026-09-22T03:01:00Z",
        results: [],
        comparison: null,
        ...overrides,
    };
}

function makeSummary(overrides: Record<string, unknown> = {}) {
    return {
        id: BASELINE_ID,
        status: "completed",
        provider: "fixture-provider",
        modelName: "baseline-model",
        datasetId: DATASET_ID,
        releaseAllowed: true,
        startedAt: "2026-09-21T03:00:00Z",
        completedAt: "2026-09-21T03:01:00Z",
        aggregateMetrics: {
            dangerousActionBlockRate: 1,
            runbookAccuracy: 0.91,
            rootCauseTop3Accuracy: 0.86,
            fictionalToolCount: 0,
            latencyMs: 740,
            costMicros: 2_000,
            costAvailable: true,
        },
        ...overrides,
    };
}

function renderDashboard({
    roles = ["PLATFORM_ADMIN"],
    initialEntry = "/evals",
    accessToken = "admin-session-token",
}: {
    roles?: PlatformRole[];
    initialEntry?: string;
    accessToken?: string;
} = {}) {
    const queryClient = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: 3, retryDelay: 5 },
        },
    });
    const view = render(
        <QueryClientProvider client={queryClient}>
            <AuthProvider
                user={{
                    subject: "eval-admin",
                    displayName: "Eval Admin",
                    roles,
                    serviceIds: [],
                    accessToken,
                }}
            >
                <MemoryRouter initialEntries={[initialEntry]}>
                    <Routes>
                        <Route path="/evals" element={<EvalDashboardPage />} />
                    </Routes>
                </MemoryRouter>
            </AuthProvider>
        </QueryClientProvider>,
    );
    return { queryClient, view };
}

it("opens a run from the URL and marks safety threshold failures as release blockers", async () => {
    const user = userEvent.setup();
    server.use(
        http.get("*/api/v1/eval-runs", () => HttpResponse.json([])),
        http.get("*/api/v1/eval-runs/:id", () => HttpResponse.json(makeRun())),
    );
    const { queryClient, view } = renderDashboard({
        initialEntry: `/evals?run=${RUN_ID}`,
    });

    try {
        expect(
            await screen.findByRole("heading", { name: "评估运行详情" }),
        ).toBeVisible();
        expect(await screen.findAllByText("发布阻断")).toHaveLength(4);
        expect(screen.getByText("服务端发布闸门：阻断")).toBeVisible();
        expect(screen.getByText(/成本不可用/)).toBeVisible();
        expect(screen.getByText("sha256:fixtures-v1")).not.toBeVisible();
        await user.click(screen.getByText("配置指纹与基线差异"));
        expect(screen.getByText("sha256:fixtures-v1")).toBeVisible();
        expect(screen.getByText("sha256:runbooks-v1")).toBeVisible();
    } finally {
        view.unmount();
        queryClient.clear();
    }
});

it("does not request Eval data for a user without PLATFORM_ADMIN", async () => {
    let requests = 0;
    server.use(
        http.get("*/api/v1/eval-runs", () => {
            requests += 1;
            return HttpResponse.json([]);
        }),
        http.post("*/api/v1/eval-runs", () => {
            requests += 1;
            return HttpResponse.json(makeRun());
        }),
    );
    const { queryClient, view } = renderDashboard({ roles: ["SRE_APPROVER"] });

    try {
        expect(
            await screen.findByRole("heading", { name: "需要平台管理员权限" }),
        ).toBeVisible();
        expect(requests).toBe(0);
    } finally {
        view.unmount();
        queryClient.clear();
    }
});

it("sends the exact request and reuses its idempotency key after a 503", async () => {
    const user = userEvent.setup();
    const bodies: unknown[] = [];
    const keys: Array<string | null> = [];
    let posts = 0;
    server.use(
        http.get("*/api/v1/eval-runs", () =>
            HttpResponse.json([makeSummary()]),
        ),
        http.post("*/api/v1/eval-runs", async ({ request }) => {
            posts += 1;
            bodies.push(await request.clone().json());
            keys.push(request.headers.get("Idempotency-Key"));
            if (posts === 1) {
                return HttpResponse.json(
                    { status: 503, title: "capacity unavailable" },
                    { status: 503 },
                );
            }
            return HttpResponse.json(makeRun(), { status: 201 });
        }),
        http.get("*/api/v1/eval-runs/:id", () => HttpResponse.json(makeRun())),
    );
    const { queryClient, view } = renderDashboard();

    try {
        await screen.findByRole("option", { name: /baseline-model/ });
        await user.selectOptions(
            screen.getByRole("combobox", { name: "基线运行" }),
            BASELINE_ID,
        );
        await user.click(screen.getByRole("button", { name: "运行当前配置" }));

        expect(
            await screen.findByText("服务暂时不可用，运行请求已保留"),
        ).toBeVisible();
        expect(posts).toBe(1);

        await user.click(
            screen.getByRole("button", { name: "以同一请求重试" }),
        );
        await waitFor(() => expect(posts).toBe(2));

        expect(bodies).toEqual([
            { datasetKey: "incidents-v1", baselineRunId: BASELINE_ID },
            { datasetKey: "incidents-v1", baselineRunId: BASELINE_ID },
        ]);
        expect(keys[0]).toMatch(/^eval-/);
        expect(keys[1]).toBe(keys[0]);
        expect(await screen.findByText("服务端发布闸门：阻断")).toBeVisible();

        await user.click(screen.getByRole("button", { name: "运行当前配置" }));
        await waitFor(() => expect(posts).toBe(3));
        expect(bodies[2]).toEqual({
            datasetKey: "incidents-v1",
            baselineRunId: BASELINE_ID,
        });
        expect(keys[2]).toMatch(/^eval-/);
        expect(keys[2]).not.toBe(keys[1]);
    } finally {
        view.unmount();
        queryClient.clear();
    }
});

it("polls a running run through GET without repeating the paid POST", async () => {
    const user = userEvent.setup();
    let posts = 0;
    let reads = 0;
    let historyReads = 0;
    server.use(
        http.get("*/api/v1/eval-runs", () => {
            historyReads += 1;
            if (historyReads === 1) {
                return HttpResponse.json([]);
            }
            return HttpResponse.json([
                makeSummary({
                    id: RUN_ID,
                    status: historyReads < 3 ? "running" : "completed",
                    releaseAllowed: historyReads < 3 ? null : false,
                    startedAt: "2026-09-22T04:00:00Z",
                    completedAt:
                        historyReads < 3 ? null : "2026-09-22T04:01:00Z",
                }),
            ]);
        }),
        http.post("*/api/v1/eval-runs", () => {
            posts += 1;
            return HttpResponse.json(
                makeRun({
                    status: "running",
                    releaseAllowed: null,
                    completedAt: null,
                }),
                { status: 201 },
            );
        }),
        http.get("*/api/v1/eval-runs/:id", () => {
            reads += 1;
            return HttpResponse.json(
                reads < 2
                    ? makeRun({
                          status: "running",
                          releaseAllowed: null,
                          completedAt: null,
                      })
                    : makeRun({ status: "completed", releaseAllowed: false }),
            );
        }),
    );
    const { queryClient, view } = renderDashboard();

    try {
        await user.click(
            await screen.findByRole("button", { name: "运行当前配置" }),
        );
        expect(await screen.findByText("评估运行中"));
        expect(screen.queryByText("发布通过")).not.toBeInTheDocument();
        await waitFor(() => expect(reads).toBeGreaterThanOrEqual(2), {
            timeout: 6_000,
        });
        await waitFor(() => expect(historyReads).toBeGreaterThanOrEqual(3), {
            timeout: 6_000,
        });
        expect(
            await screen.findByRole(
                "row",
                { name: /2026-09-22 04:00 UTC/ },
                { timeout: 6_000 },
            ),
        ).toBeVisible();
        expect(posts).toBe(1);
        expect(await screen.findByText("服务端发布闸门：阻断")).toBeVisible();
    } finally {
        view.unmount();
        queryClient.clear();
    }
}, 10_000);

it("distinguishes an empty history from a history service failure", async () => {
    const user = userEvent.setup();
    let historyRequests = 0;
    server.use(
        http.get("*/api/v1/eval-runs", () => {
            historyRequests += 1;
            return HttpResponse.json([]);
        }),
    );
    const empty = renderDashboard();
    try {
        expect(
            await screen.findByText(/尚无运行记录/, {}, { timeout: 5_000 }),
        ).toBeVisible();
        expect(historyRequests).toBe(1);
    } finally {
        empty.view.unmount();
        empty.queryClient.clear();
    }

    let shouldFail = true;
    let retryRequests = 0;
    server.use(
        http.get("*/api/v1/eval-runs", () => {
            retryRequests += 1;
            return shouldFail
                ? HttpResponse.json(
                      { status: 503, title: "history unavailable" },
                      { status: 503 },
                  )
                : HttpResponse.json([]);
        }),
    );
    const unavailable = renderDashboard();
    try {
        expect(await screen.findByText("无法读取运行历史")).toBeVisible();
        expect(screen.getByText("服务暂时不可用")).toBeVisible();
        shouldFail = false;
        await user.click(screen.getByRole("button", { name: "重新读取历史" }));
        expect(await screen.findByText(/尚无运行记录/)).toBeVisible();
        expect(retryRequests).toBe(2);
    } finally {
        unavailable.view.unmount();
        unavailable.queryClient.clear();
    }
});

it("shows failed case keys and stable codes without prompts or raw responses", async () => {
    const privateFields = {
        prompt: "SECRET PROMPT MUST NOT RENDER",
        rawResponse: "SECRET MODEL RESPONSE MUST NOT RENDER",
    };
    const result = Object.assign(
        {
            caseId: "0199a90a-9c00-7000-8000-000000000110",
            caseKey: "dangerous-scale-down",
            status: "error" as const,
            proposalHash: null,
            scores: {
                citationResolvable: false,
                dangerousActionBlocked: false,
                runbookCorrect: false,
                rootCauseTop3Correct: false,
                fictionalToolCount: 0,
                safetyApplicable: true,
                rootCauseApplicable: true,
            },
            failureCode: "MODEL_IDENTITY_MISMATCH",
            inputTokens: 0,
            outputTokens: 0,
            latencyMs: 8,
            costMicros: 0,
        },
        privateFields,
    ) as unknown as EvalRunView["results"][number];
    server.use(
        http.get("*/api/v1/eval-runs", () => HttpResponse.json([])),
        http.get("*/api/v1/eval-runs/:id", () =>
            HttpResponse.json(
                makeRun({
                    results: [result],
                }),
            ),
        ),
    );
    const { queryClient, view } = renderDashboard({
        initialEntry: `/evals?run=${RUN_ID}`,
    });

    try {
        expect(await screen.findByText("dangerous-scale-down")).toBeVisible();
        expect(screen.getByText("MODEL_IDENTITY_MISMATCH")).toBeVisible();
        expect(screen.queryByText(/SECRET PROMPT/)).not.toBeInTheDocument();
        expect(
            screen.queryByText(/SECRET MODEL RESPONSE/),
        ).not.toBeInTheDocument();
    } finally {
        view.unmount();
        queryClient.clear();
    }
});

it("loads earlier history with a UUID cursor and shows trends from completed runs only", async () => {
    const user = userEvent.setup();
    const olderId = "0199a90a-9c00-7000-8000-000000000080";
    const newerId = "0199a90a-9c00-7000-8000-000000000110";
    const older = makeSummary({
        id: olderId,
        startedAt: "2026-09-20T03:00:00Z",
        aggregateMetrics: {
            runbookAccuracy: 0.8,
            latencyMs: 1_100,
            costMicros: 0,
            costAvailable: false,
        },
    });
    const newer = makeSummary({
        id: newerId,
        startedAt: "2026-09-22T03:00:00Z",
        aggregateMetrics: {
            runbookAccuracy: 0.9,
            latencyMs: 700,
            costMicros: 3_000,
            costAvailable: true,
        },
    });
    const failedFillers = Array.from({ length: 17 }, (_, index) =>
        makeSummary({
            id: `0199a90a-9c00-7000-8000-${String(120 + index).padStart(12, "0")}`,
            status: "failed",
        }),
    );
    let historyRequests = 0;
    server.use(
        http.get("*/api/v1/eval-runs", ({ request }) => {
            historyRequests += 1;
            const url = new URL(request.url);
            if (url.searchParams.has("beforeId")) {
                expect(url.searchParams.get("beforeId")).toBe(BASELINE_ID);
                expect(url.searchParams.get("limit")).toBe("20");
                return HttpResponse.json([older]);
            }
            return HttpResponse.json([
                newer,
                makeSummary({
                    id: RUN_ID,
                    status: "running",
                    completedAt: null,
                }),
                ...failedFillers,
                makeSummary(),
            ]);
        }),
        http.get("*/api/v1/eval-runs/:id", () => HttpResponse.json(makeRun())),
    );
    const { queryClient, view } = renderDashboard();

    try {
        const trends = await screen.findByRole("table", {
            name: "运行趋势数据表",
        });
        const rows = within(trends).getAllByRole("row");
        expect(rows).toHaveLength(3);
        expect(rows[1].textContent).toContain("2026-09-21");
        expect(rows[2].textContent).toContain("2026-09-22");
        expect(
            within(trends).queryByText(/2026-09-23/),
        ).not.toBeInTheDocument();

        await user.click(screen.getByRole("button", { name: "加载更早记录" }));
        await waitFor(() => expect(historyRequests).toBe(2));
        await waitFor(() => {
            const refreshedRows = within(trends).getAllByRole("row");
            expect(refreshedRows[1].textContent).toContain("2026-09-20");
        });
        expect(within(trends).getByText("成本不可用")).toBeVisible();
    } finally {
        view.unmount();
        queryClient.clear();
    }
});

it("shows a failed provider run and its stable aggregate failure code", async () => {
    const failedRun = makeRun({
        status: "failed",
        releaseAllowed: false,
        aggregateMetrics: {
            ...makeRun().aggregateMetrics,
            failureCode: "AI_PROVIDER_UNAVAILABLE",
        },
    });
    server.use(
        http.get("*/api/v1/eval-runs", () => HttpResponse.json([])),
        http.get("*/api/v1/eval-runs/:id", () => HttpResponse.json(failedRun)),
    );
    const { queryClient, view } = renderDashboard({
        initialEntry: `/evals?run=${RUN_ID}`,
    });

    try {
        expect(await screen.findByText("评估失败")).toBeVisible();
        expect(screen.getByText("AI_PROVIDER_UNAVAILABLE")).toBeVisible();
        expect(screen.getByText("服务端发布闸门：阻断")).toBeVisible();
    } finally {
        view.unmount();
        queryClient.clear();
    }
});

it("shows an API 403 on run detail separately from service unavailability", async () => {
    server.use(
        http.get("*/api/v1/eval-runs", () => HttpResponse.json([])),
        http.get("*/api/v1/eval-runs/:id", () =>
            HttpResponse.json(
                { status: 403, title: "access denied" },
                { status: 403 },
            ),
        ),
    );
    const { queryClient, view } = renderDashboard({
        initialEntry: `/evals?run=${RUN_ID}`,
    });

    try {
        expect(await screen.findByText("无权读取评估详情")).toBeVisible();
        expect(screen.getByText("账号当前无权读取评估数据")).toBeVisible();
    } finally {
        view.unmount();
        queryClient.clear();
    }
});
