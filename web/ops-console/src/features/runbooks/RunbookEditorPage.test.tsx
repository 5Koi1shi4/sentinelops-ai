import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { Link, useLocation } from "react-router-dom";

import { server } from "../../mocks/handlers";
import { RunbookEditorPage } from "./RunbookEditorPage";
import {
    AUTHOR_ID,
    DRAFT_ID,
    PUBLISHED_ID,
    REVIEWER_ID,
    RUNBOOK_KEY,
    SERVICE_ID,
    independentAdmin,
    makePublishedVersion,
    makeVersion,
    mockEditorApi,
    observer,
    renderEditor,
    runbookAdmin,
    runbookDefinition,
    serviceRows,
} from "./runbookTestHelpers";

function EditorTestHarness({ switchTo }: { switchTo: string }) {
    const location = useLocation();
    return (
        <>
            <output aria-label="测试路由">{location.pathname}</output>
            <Link to={switchTo}>切换到目标版本</Link>
            <RunbookEditorPage />
        </>
    );
}

it("saves structured draft fields and Markdown with revision and idempotency headers", async () => {
    const user = userEvent.setup();
    const updates: Request[] = [];
    mockEditorApi();
    server.use(
        http.put("*/api/v1/runbook-versions/:id", async ({ request }) => {
            updates.push(request.clone());
            return HttpResponse.json(makeVersion({ revision: 8 }));
        }),
    );
    renderEditor();

    const markdown = await screen.findByLabelText("操作说明（Markdown）");
    await user.clear(markdown);
    await user.type(markdown, "确认连接池状态后执行恢复，再等待健康检查通过。");
    const attempts = screen.getByRole("spinbutton", { name: "验证次数" });
    await user.clear(attempts);
    await user.type(attempts, "8");
    const interval = screen.getByRole("spinbutton", {
        name: "验证间隔（秒）",
    });
    await user.clear(interval);
    await user.type(interval, "15");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    await waitFor(() => expect(updates).toHaveLength(1));
    expect(updates[0].headers.get("Authorization")).toBe(
        `Bearer ${runbookAdmin.accessToken}`,
    );
    expect(updates[0].headers.get("If-Match")).toBe('"7"');
    expect(updates[0].headers.get("Idempotency-Key")).toBeTruthy();
    expect(await updates[0].json()).toMatchObject({
        definition: {
            runbookKey: RUNBOOK_KEY,
            risk: "R1",
            adapterId: "demo-http",
            parameters: {
                properties: { replicas: { minimum: 1, maximum: 1 } },
                additionalProperties: false,
            },
            steps: [
                {
                    stepId: "recover-one",
                    operation: "recover_connection_pool",
                },
            ],
            verification: {
                probe: "demo_checkout_health",
                successThreshold: 1,
                attempts: 8,
                intervalSeconds: 15,
            },
            rollback: null,
        },
        markdown: "确认连接池状态后执行恢复，再等待健康检查通过。",
    });
});

it("lets an observer read a published version without authoring controls", async () => {
    const published = makePublishedVersion({
        id: PUBLISHED_ID,
        markdown: "已发布说明：检查连接池，再验证结账健康状态。",
    });
    mockEditorApi({ current: published, versions: [published] });
    let diffRequests = 0;
    server.use(
        http.get("*/api/v1/runbook-versions/:id/diff", () => {
            diffRequests += 1;
            return HttpResponse.json({});
        }),
    );

    renderEditor(observer, `/runbooks/${RUNBOOK_KEY}/versions/${PUBLISHED_ID}`);

    expect(
        await screen.findByText("已发布说明：检查连接池，再验证结账健康状态。"),
    ).toBeVisible();
    expect(
        screen.queryByRole("button", { name: "保存草稿" }),
    ).not.toBeInTheDocument();
    expect(
        screen.queryByRole("button", { name: "创建下一版草稿" }),
    ).not.toBeInTheDocument();
    expect(
        screen.queryByRole("button", { name: "确认独立评审" }),
    ).not.toBeInTheDocument();
    expect(
        screen.queryByRole("button", { name: "发布不可变版本" }),
    ).not.toBeInTheDocument();
    expect(
        screen.queryByRole("heading", { name: "发布前结构差异" }),
    ).not.toBeInTheDocument();
    expect(diffRequests).toBe(0);
});

it("does not offer review when the server says this administrator is not independent", async () => {
    mockEditorApi({
        current: makeVersion({
            authorPrincipalId: AUTHOR_ID,
            reviewerPrincipalId: null,
            canReview: false,
        }),
    });

    renderEditor(runbookAdmin);

    expect(
        await screen.findByText("需由具备独立资格的管理员评审"),
    ).toBeVisible();
    expect(
        screen.queryByRole("button", { name: "确认独立评审" }),
    ).not.toBeInTheDocument();
});

it("keeps local edits when a save conflicts with a newer server revision", async () => {
    const user = userEvent.setup();
    const updates: Request[] = [];
    mockEditorApi();
    server.use(
        http.put("*/api/v1/runbook-versions/:id", async ({ request }) => {
            updates.push(request.clone());
            return HttpResponse.json(
                {
                    status: 412,
                    title: "版本已更新",
                    detail: "请读取最新版本并合并草稿。",
                },
                { status: 412 },
            );
        }),
    );
    renderEditor();

    const markdown = await screen.findByLabelText("操作说明（Markdown）");
    await user.clear(markdown);
    await user.type(markdown, "本地尚未保存的操作内容");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
        "服务器版本已变化",
    );
    expect(markdown).toHaveValue("本地尚未保存的操作内容");
    expect(screen.queryByText("草稿已保存")).not.toBeInTheDocument();
    expect(updates).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "刷新服务端版本" }));
    expect(
        await screen.findByRole("button", {
            name: "我已合并本地修改，继续编辑",
        }),
    ).toBeVisible();
    expect(markdown).toHaveValue("本地尚未保存的操作内容");
});

it("retries an uncertain save with the same key and uses a new key after edits", async () => {
    const user = userEvent.setup();
    const updates: Request[] = [];
    mockEditorApi();
    server.use(
        http.put("*/api/v1/runbook-versions/:id", async ({ request }) => {
            updates.push(request.clone());
            if (updates.length === 1 || updates.length === 3) {
                return HttpResponse.error();
            }
            const body = (await request.json()) as {
                definition: typeof runbookDefinition;
                markdown: string;
            };
            return HttpResponse.json(
                makeVersion({
                    revision: 8,
                    definition: body.definition,
                    markdown: body.markdown,
                }),
            );
        }),
    );
    renderEditor();

    const markdown = await screen.findByLabelText("操作说明（Markdown）");
    await user.clear(markdown);
    await user.type(markdown, "首次提交的本地说明。");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    await screen.findByRole("button", { name: "重试相同命令" });

    await user.click(screen.getByRole("button", { name: "重试相同命令" }));
    await waitFor(() => expect(updates).toHaveLength(2));
    expect(updates[0].headers.get("Idempotency-Key")).toBe(
        updates[1].headers.get("Idempotency-Key"),
    );
    expect(await updates[0].json()).toEqual(await updates[1].json());

    await user.clear(markdown);
    await user.type(markdown, "修改后的提交说明。");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    await waitFor(() => expect(updates).toHaveLength(3));
    expect(updates[2].headers.get("Idempotency-Key")).not.toBe(
        updates[1].headers.get("Idempotency-Key"),
    );
    expect(await updates[2].json()).toMatchObject({
        markdown: "修改后的提交说明。",
    });
});

it("locks an uncertain new-draft command until the same request is retried", async () => {
    const user = userEvent.setup();
    const creates: Request[] = [];
    server.use(
        http.get("*/api/v1/services", () => HttpResponse.json(serviceRows)),
        http.post("*/api/v1/runbooks/:key/versions", async ({ request }) => {
            creates.push(request.clone());
            return HttpResponse.json(
                {
                    status: 503,
                    title: "服务暂不可用",
                    detail: "创建结果暂时无法确认。",
                },
                { status: 503 },
            );
        }),
    );
    renderEditor(runbookAdmin, "/runbooks/new");

    await user.type(screen.getByLabelText("Runbook Key"), "RB-CHECKOUT-02");
    await user.type(
        screen.getByLabelText("人类可读名称"),
        "恢复 Checkout 服务",
    );
    await user.type(screen.getByLabelText("责任团队"), "支付平台组");
    await user.selectOptions(screen.getByLabelText("所属服务"), SERVICE_ID);
    const markdown = screen.getByLabelText("操作说明（Markdown）");
    await user.type(markdown, "先检查，再执行受控恢复。");
    await user.click(screen.getByRole("button", { name: "创建 Runbook 草稿" }));
    await screen.findByRole("button", { name: "重试相同命令" });

    expect(screen.getByLabelText("Runbook Key")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("人类可读名称")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("责任团队")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("所属服务")).toBeDisabled();
    expect(markdown).toHaveAttribute("readonly");
    expect(creates).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "重试相同命令" }));
    await waitFor(() => expect(creates).toHaveLength(2));
    expect(creates[0].headers.get("Idempotency-Key")).toBe(
        creates[1].headers.get("Idempotency-Key"),
    );
    expect(await creates[0].json()).toEqual(await creates[1].json());
});

it("allows editing after a definite client-side rejection", async () => {
    const user = userEvent.setup();
    const updates: Request[] = [];
    mockEditorApi();
    server.use(
        http.put("*/api/v1/runbook-versions/:id", async ({ request }) => {
            updates.push(request.clone());
            if (updates.length === 1) {
                return HttpResponse.json(
                    {
                        status: 400,
                        title: "验证失败",
                        detail: "操作说明不符合服务端校验。",
                    },
                    { status: 400 },
                );
            }
            return HttpResponse.json(makeVersion({ revision: 8 }));
        }),
    );
    renderEditor();

    const markdown = await screen.findByLabelText("操作说明（Markdown）");
    await user.clear(markdown);
    await user.type(markdown, "服务端拒绝后修正的说明。");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
        "操作说明不符合服务端校验",
    );
    expect(
        screen.queryByRole("button", { name: "重试相同命令" }),
    ).not.toBeInTheDocument();
    expect(markdown).not.toHaveAttribute("readonly");

    await user.clear(markdown);
    await user.type(markdown, "服务端校验通过的说明。");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    await waitFor(() => expect(updates).toHaveLength(2));
    expect(updates[1].headers.get("Idempotency-Key")).not.toBe(
        updates[0].headers.get("Idempotency-Key"),
    );
});

it("keeps the same command key when the server reports it is still in progress", async () => {
    const user = userEvent.setup();
    const updates: Request[] = [];
    mockEditorApi();
    server.use(
        http.put("*/api/v1/runbook-versions/:id", async ({ request }) => {
            updates.push(request.clone());
            if (updates.length === 1) {
                return HttpResponse.json(
                    {
                        status: 409,
                        title: "命令仍在处理中",
                        detail: "关联的幂等键仍在处理中。",
                        errorCode: "IDEMPOTENCY_IN_PROGRESS",
                    },
                    { status: 409 },
                );
            }
            return HttpResponse.json(makeVersion({ revision: 8 }));
        }),
    );
    renderEditor();

    const markdown = await screen.findByLabelText("操作说明（Markdown）");
    await user.clear(markdown);
    await user.type(markdown, "等待相同命令处理完成。\n");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    await screen.findByRole("button", { name: "重试相同命令" });
    expect(screen.getByRole("alert")).toHaveTextContent(
        "关联的幂等键仍在处理中",
    );

    await user.click(screen.getByRole("button", { name: "重试相同命令" }));
    await waitFor(() => expect(updates).toHaveLength(2));
    expect(updates[1].headers.get("Idempotency-Key")).toBe(
        updates[0].headers.get("Idempotency-Key"),
    );
    expect(await updates[1].json()).toEqual(await updates[0].json());
});

it("clears a failed save retry when navigating to another version route", async () => {
    const user = userEvent.setup();
    const versionA = makeVersion({
        id: DRAFT_ID,
        displayName: "版本 A：数据库连接池",
    });
    const versionB = makeVersion({
        id: "0199a90a-9c00-7000-8000-000000000033",
        versionNumber: 3,
        revision: 2,
        displayName: "版本 B：缓存服务",
        markdown: "缓存服务恢复说明。",
    });
    mockEditorApi({
        current: versionA,
        versions: [makePublishedVersion(), versionA, versionB],
    });
    server.use(
        http.put("*/api/v1/runbook-versions/:id", () =>
            HttpResponse.json(
                {
                    status: 503,
                    title: "服务暂不可用",
                    detail: "保存结果暂时无法确认。",
                },
                { status: 503 },
            ),
        ),
    );
    const pathA = `/runbooks/${RUNBOOK_KEY}/versions/${versionA.id}`;
    const pathB = `/runbooks/${RUNBOOK_KEY}/versions/${versionB.id}`;
    renderEditor(
        runbookAdmin,
        pathA,
        <EditorTestHarness switchTo={pathB} />,
    );

    const markdown = await screen.findByLabelText("操作说明（Markdown）");
    await user.clear(markdown);
    await user.type(markdown, "版本 A 尚未确认保存的说明。");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    await screen.findByRole("button", { name: "重试相同命令" });
    await user.click(screen.getByRole("link", { name: "切换到目标版本" }));

    expect(
        await screen.findByRole("heading", { name: "版本 B：缓存服务" }),
    ).toBeVisible();
    expect(screen.getByLabelText("测试路由")).toHaveTextContent(pathB);
    expect(
        screen.queryByRole("button", { name: "重试相同命令" }),
    ).not.toBeInTheDocument();
});

it("does not navigate away from the selected version when an old create resolves", async () => {
    const user = userEvent.setup();
    const versionB = makePublishedVersion({
        id: PUBLISHED_ID,
        displayName: "当前版本 B：现有 Runbook",
    });
    const createdDraft = makeVersion({
        id: "0199a90a-9c00-7000-8000-000000000034",
        runbookKey: "RB-CHECKOUT-04",
        displayName: "新建结果",
        definition: {
            ...runbookDefinition,
            runbookKey: "RB-CHECKOUT-04",
        },
    });
    let releaseCreate!: () => void;
    const createResponse = new Promise<void>((resolve) => {
        releaseCreate = resolve;
    });
    mockEditorApi({ current: versionB, versions: [versionB] });
    server.use(
        http.post("*/api/v1/runbooks/:key/versions", async () => {
            await createResponse;
            return HttpResponse.json(createdDraft, { status: 201 });
        }),
        http.get("*/api/v1/runbook-versions/:id", ({ params }) =>
            HttpResponse.json(
                String(params.id) === createdDraft.id ? createdDraft : versionB,
            ),
        ),
    );
    const pathB = `/runbooks/${RUNBOOK_KEY}/versions/${versionB.id}`;
    const { queryClient } = renderEditor(
        runbookAdmin,
        "/runbooks/new",
        <EditorTestHarness switchTo={pathB} />,
    );

    await user.type(screen.getByLabelText("Runbook Key"), "RB-CHECKOUT-04");
    await user.type(screen.getByLabelText("人类可读名称"), "新建结果");
    await user.type(screen.getByLabelText("责任团队"), "支付平台组");
    await user.selectOptions(screen.getByLabelText("所属服务"), SERVICE_ID);
    await user.type(
        screen.getByLabelText("操作说明（Markdown）"),
        "受控恢复步骤。",
    );
    await user.click(screen.getByRole("button", { name: "创建 Runbook 草稿" }));
    await waitFor(() =>
        expect(
            queryClient
                .getMutationCache()
                .getAll()
                .some((mutation) => mutation.state.status === "pending"),
        ).toBe(true),
    );
    await user.click(screen.getByRole("link", { name: "切换到目标版本" }));
    expect(
        await screen.findByRole("heading", { name: versionB.displayName }),
    ).toBeVisible();

    releaseCreate();
    await waitFor(() =>
        expect(
            queryClient
                .getMutationCache()
                .getAll()
                .some(
                    (mutation) =>
                        mutation.state.status === "success" &&
                        mutation.options.mutationKey?.[0] === "runbook-create",
                ),
        ).toBe(true),
    );
    await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(screen.getByLabelText("测试路由")).toHaveTextContent(pathB);
    expect(
        screen.getByRole("heading", { name: versionB.displayName }),
    ).toBeVisible();
});

it("shows the structural diff and requires a second confirmation before publishing", async () => {
    const user = userEvent.setup();
    const publishes: Request[] = [];
    const reviewedDraft = makeVersion({
        reviewerPrincipalId: REVIEWER_ID,
        canReview: false,
    });
    mockEditorApi({ current: reviewedDraft });
    server.use(
        http.post(
            "*/api/v1/runbook-versions/:id/publish",
            async ({ request }) => {
                publishes.push(request.clone());
                return HttpResponse.json(
                    makePublishedVersion({
                        id: DRAFT_ID,
                        versionNumber: 2,
                        revision: 8,
                    }),
                    { headers: { ETag: '"8"' } },
                );
            },
        ),
    );
    renderEditor(independentAdmin);

    expect(await screen.findByText("参数范围")).toBeVisible();
    expect(screen.getByText("验证规则")).toBeVisible();
    expect(screen.getAllByText("1–1 个实例")).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "发布不可变版本" }));

    expect(
        await screen.findByRole("group", { name: "发布版本 2" }),
    ).toHaveTextContent("发布版本 2");
    expect(screen.getByRole("group", { name: "发布版本 2" })).toHaveTextContent(
        "风险：R1",
    );
    expect(publishes).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "取消" }));
    const publishTrigger = screen.getByRole("button", {
        name: "发布不可变版本",
    });
    expect(publishTrigger).toHaveFocus();
    await user.click(publishTrigger);
    await user.click(
        screen.getByRole("button", { name: "确认发布不可变版本" }),
    );

    await waitFor(() => expect(publishes).toHaveLength(1));
    expect(publishes[0].headers.get("If-Match")).toBe('"7"');
    expect(publishes[0].headers.get("Idempotency-Key")).toBeTruthy();
});

it("loads every preceding version before choosing the publish baseline", async () => {
    const user = userEvent.setup();
    const current = makeVersion({
        id: "0199a90a-9c00-7000-8000-000000000080",
        versionNumber: 80,
        reviewerPrincipalId: REVIEWER_ID,
        definition: {
            ...runbookDefinition,
            verification: {
                ...runbookDefinition.verification,
                attempts: 8,
                intervalSeconds: 15,
            },
        },
    });
    const historicalVersion = (number: number, lifecycle: string) =>
        makeVersion({
            id: `0199a90a-9c00-7000-8000-${String(number + 100).padStart(12, "0")}`,
            versionNumber: number,
            revision: number,
            lifecycle,
            reviewerPrincipalId: lifecycle === "published" ? REVIEWER_ID : null,
            publishedAt:
                lifecycle === "published" ? "2026-09-20T10:00:00Z" : null,
        });
    const firstPage = Array.from({ length: 50 }, (_, index) =>
        historicalVersion(index + 1, index + 1 === 50 ? "published" : "draft"),
    );
    const secondPage = Array.from({ length: 30 }, (_, index) => {
        const versionNumber = index + 51;
        return versionNumber === 80
            ? current
            : historicalVersion(
                  versionNumber,
                  versionNumber === 79 ? "published" : "draft",
              );
    });
    const latestPublished = secondPage.find(
        (candidate) => candidate.versionNumber === 79,
    );
    const versionsById = new Map(
        [...firstPage, ...secondPage].map((candidate) => [
            candidate.id,
            candidate,
        ]),
    );
    const versionRequests: URL[] = [];
    const diffRequests: URL[] = [];
    mockEditorApi({ current, versions: firstPage });
    server.use(
        http.get("*/api/v1/runbooks/:key/versions", ({ request }) => {
            const url = new URL(request.url);
            versionRequests.push(url);
            return HttpResponse.json(
                url.searchParams.get("afterVersion") === "50"
                    ? secondPage
                    : firstPage,
            );
        }),
        http.get("*/api/v1/runbook-versions/:id", () =>
            HttpResponse.json(current, {
                headers: { ETag: `"${current.revision}"` },
            }),
        ),
        http.get(
            "*/api/v1/runbook-versions/:id/diff",
            ({ request, params }) => {
                const url = new URL(request.url);
                diffRequests.push(url);
                const before = versionsById.get(String(params.id)) ?? current;
                const after =
                    versionsById.get(
                        url.searchParams.get("otherVersionId") ?? "",
                    ) ?? current;
                return HttpResponse.json({
                    fromVersionId: before.id,
                    toVersionId: after.id,
                    definitionChanged:
                        JSON.stringify(before.definition) !==
                        JSON.stringify(after.definition),
                    markdownChanged: before.markdown !== after.markdown,
                    beforeDefinition: before.definition,
                    afterDefinition: after.definition,
                    beforeMarkdown: before.markdown,
                    afterMarkdown: after.markdown,
                });
            },
        ),
    );
    renderEditor(
        runbookAdmin,
        `/runbooks/${RUNBOOK_KEY}/versions/${current.id}`,
    );

    await screen.findByRole("button", { name: "加载更多版本" });
    expect(diffRequests).toHaveLength(0);
    expect(
        screen.getByRole("button", { name: "发布不可变版本" }),
    ).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "加载更多版本" }));
    await waitFor(() => expect(versionRequests).toHaveLength(2));
    await screen.findByRole("heading", { name: "发布前结构差异" });
    await waitFor(() => expect(diffRequests).toHaveLength(1));
    expect(diffRequests[0].pathname).toBe(
        `/api/v1/runbook-versions/${latestPublished?.id}/diff`,
    );
    expect(diffRequests[0].searchParams.get("otherVersionId")).toBe(current.id);
    const verificationRow = screen
        .getByRole("heading", { name: "验证规则" })
        .closest(".runbook-diff-row") as HTMLElement;
    const verificationCells = within(verificationRow);
    expect(
        verificationCells.getByText(/3 次尝试/).closest("p"),
    ).toHaveTextContent("基准");
    expect(
        verificationCells.getByText(/8 次尝试/).closest("p"),
    ).toHaveTextContent("提交版本");
    expect(
        screen.getByRole("button", { name: "发布不可变版本" }),
    ).toBeEnabled();
});

it("creates the next draft using identity and content from the published version", async () => {
    const user = userEvent.setup();
    const published = makePublishedVersion({
        id: PUBLISHED_ID,
        versionNumber: 3,
        markdown: "继承的已发布正文。",
    });
    const nextDraftId = "0199a90a-9c00-7000-8000-000000000033";
    const nextDraft = makeVersion({
        id: nextDraftId,
        versionNumber: 4,
        revision: 0,
        markdown: published.markdown,
    });
    const creates: Request[] = [];
    mockEditorApi({ current: published, versions: [published] });
    server.use(
        http.post("*/api/v1/runbooks/:key/versions", async ({ request }) => {
            creates.push(request.clone());
            return HttpResponse.json(nextDraft, { status: 201 });
        }),
        http.get("*/api/v1/runbook-versions/:id", ({ params }) =>
            HttpResponse.json(
                String(params.id) === nextDraftId ? nextDraft : published,
            ),
        ),
    );
    renderEditor(
        runbookAdmin,
        `/runbooks/${RUNBOOK_KEY}/versions/${PUBLISHED_ID}`,
    );

    await user.click(
        await screen.findByRole("button", { name: "创建下一版草稿" }),
    );

    await waitFor(() => expect(creates).toHaveLength(1));
    expect(await creates[0].json()).toMatchObject({
        serviceId: serviceRows[0].id,
        displayName: "恢复 Checkout 数据库连接池",
        ownerTeam: "支付平台组",
        definition: runbookDefinition,
        markdown: "继承的已发布正文。",
    });
    expect(await screen.findByText("草稿版本 4")).toBeVisible();
});

it("creates a new draft from structured identity and bounded fields", async () => {
    const user = userEvent.setup();
    const creates: Request[] = [];
    const createdDraft = makeVersion({
        id: DRAFT_ID,
        runbookKey: "RB-CHECKOUT-01",
        displayName: "恢复 Checkout 服务",
        definition: { ...runbookDefinition, runbookKey: "RB-CHECKOUT-01" },
        markdown: "完成连接池恢复步骤。",
    });
    server.use(
        http.get("*/api/v1/services", () => HttpResponse.json(serviceRows)),
        http.post("*/api/v1/runbooks/:key/versions", async ({ request }) => {
            creates.push(request.clone());
            return HttpResponse.json(createdDraft, { status: 201 });
        }),
        http.get("*/api/v1/runbooks/:key/versions", () =>
            HttpResponse.json([createdDraft]),
        ),
        http.get("*/api/v1/runbook-versions/:id", () =>
            HttpResponse.json(createdDraft),
        ),
    );
    renderEditor(runbookAdmin, "/runbooks/new");

    await user.type(
        await screen.findByLabelText("Runbook Key"),
        "RB-CHECKOUT-01",
    );
    await user.type(
        screen.getByLabelText("人类可读名称"),
        "恢复 Checkout 服务",
    );
    await user.type(screen.getByLabelText("责任团队"), "支付平台组");
    await user.selectOptions(screen.getByLabelText("所属服务"), SERVICE_ID);
    await user.type(
        screen.getByLabelText("操作说明（Markdown）"),
        "完成连接池恢复步骤。",
    );
    await user.click(screen.getByRole("button", { name: "创建 Runbook 草稿" }));

    await waitFor(() => expect(creates).toHaveLength(1));
    expect(creates[0].url).toContain("/runbooks/RB-CHECKOUT-01/versions");
    expect(creates[0].headers.get("Idempotency-Key")).toBeTruthy();
    expect(creates[0].headers.get("If-Match")).toBeNull();
    expect(await creates[0].json()).toMatchObject({
        serviceId: SERVICE_ID,
        displayName: "恢复 Checkout 服务",
        ownerTeam: "支付平台组",
        definition: {
            ...runbookDefinition,
            runbookKey: "RB-CHECKOUT-01",
            verification: {
                ...runbookDefinition.verification,
                attempts: 3,
                intervalSeconds: 2,
            },
        },
        markdown: "完成连接池恢复步骤。",
    });
});

it("loads additional service choices using the last service key", async () => {
    const user = userEvent.setup();
    const targetServiceId = "0199a90a-9c00-7000-8000-000000000051";
    const serviceRequests: URL[] = [];
    const firstPage = Array.from({ length: 50 }, (_, index) => ({
        id: `0199a90a-9c00-7000-8000-${String(index + 1).padStart(12, "0")}`,
        serviceKey: `svc-${String(index + 1).padStart(3, "0")}`,
        displayName: `服务 ${index + 1}`,
        ownerTeam: "平台组",
    }));
    server.use(
        http.get("*/api/v1/services", ({ request }) => {
            const url = new URL(request.url);
            serviceRequests.push(url);
            return HttpResponse.json(
                url.searchParams.get("afterKey") === "svc-050"
                    ? [
                          {
                              id: targetServiceId,
                              serviceKey: "svc-051",
                              displayName: "目标服务",
                              ownerTeam: "目标团队",
                          },
                      ]
                    : firstPage,
            );
        }),
    );
    renderEditor(
        { ...runbookAdmin, serviceIds: [targetServiceId] },
        "/runbooks/new",
    );

    expect(
        await screen.findByRole("button", { name: "加载更多服务" }),
    ).toBeVisible();
    expect(serviceRequests[0].searchParams.get("afterKey")).toBe("");
    expect(serviceRequests[0].searchParams.get("limit")).toBe("50");
    await user.click(screen.getByRole("button", { name: "加载更多服务" }));

    expect(
        await screen.findByRole("option", { name: "svc-051 · 目标服务" }),
    ).toBeInTheDocument();
    expect(serviceRequests[1].searchParams.get("afterKey")).toBe("svc-050");
});

it("allows only a server-authorized independent administrator to review", async () => {
    const user = userEvent.setup();
    const reviews: Request[] = [];
    const reviewable = makeVersion({
        authorPrincipalId: AUTHOR_ID,
        reviewerPrincipalId: null,
        canReview: true,
    });
    mockEditorApi({ current: reviewable });
    server.use(
        http.post(
            "*/api/v1/runbook-versions/:id/review",
            async ({ request }) => {
                reviews.push(request.clone());
                return HttpResponse.json(
                    makeVersion({ reviewerPrincipalId: REVIEWER_ID }),
                );
            },
        ),
    );
    renderEditor(independentAdmin);

    await user.click(
        await screen.findByRole("button", { name: "确认独立评审" }),
    );

    await waitFor(() => expect(reviews).toHaveLength(1));
    expect(reviews[0].headers.get("If-Match")).toBe('"7"');
    expect(reviews[0].headers.get("Idempotency-Key")).toBeTruthy();
});
