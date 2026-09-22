import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import type { components } from "../../api/generated";
import { server } from "../../mocks/handlers";
import {
    PUBLISHED_ID,
    RUNBOOK_KEY,
    observer,
    renderRunbookList,
    runbookAdmin,
} from "./runbookTestHelpers";

function makeRunbookSummary(
    index: number,
    lifecycle: components["schemas"]["RunbookSummary"]["lifecycle"] = "published",
): components["schemas"]["RunbookSummary"] {
    const runbookKey =
        index === 1 ? RUNBOOK_KEY : `RB-POOL-${String(index).padStart(3, "0")}`;
    return {
        id: `0199a90a-9c00-7000-8000-${String(index).padStart(12, "0")}`,
        runbookKey,
        serviceId: "0199a90a-9c00-7000-8000-000000000010",
        serviceKey: "checkout-api",
        displayName: `连接池恢复流程 ${index}`,
        ownerTeam: "支付平台组",
        latestVersionId:
            index === 1
                ? PUBLISHED_ID
                : `0199a90a-9c00-7000-8000-${String(index + 50).padStart(12, "0")}`,
        latestVersionNumber: index === 1 ? 3 : index,
        lifecycle,
        riskLevel: "R1",
    };
}

it("shows an empty state and a create entry point to an administrator", async () => {
    server.use(http.get("*/api/v1/runbooks", () => HttpResponse.json([])));
    renderRunbookList(runbookAdmin);

    expect(await screen.findByText("还没有 Runbook")).toBeVisible();
    expect(
        screen
            .getAllByRole("link", { name: "新建 Runbook" })
            .every((link) => link.getAttribute("href") === "/runbooks/new"),
    ).toBe(true);
});

it("shows a scoped access error when the Runbook list returns 403", async () => {
    server.use(
        http.get("*/api/v1/runbooks", () =>
            HttpResponse.json(
                {
                    status: 403,
                    title: "Forbidden",
                    detail: "没有当前服务范围的读取权限。",
                },
                { status: 403 },
            ),
        ),
    );
    renderRunbookList(runbookAdmin);

    expect(await screen.findByRole("alert")).toHaveTextContent(
        "没有当前服务范围的读取权限",
    );
    expect(
        screen.queryByRole("link", { name: "新建 Runbook" }),
    ).not.toBeInTheDocument();
});

it("continues keyset pagination from the last Runbook key", async () => {
    const user = userEvent.setup();
    const requests: URL[] = [];
    const firstPage = Array.from({ length: 50 }, (_, index) =>
        makeRunbookSummary(index + 1),
    );
    server.use(
        http.get("*/api/v1/runbooks", ({ request }) => {
            const url = new URL(request.url);
            requests.push(url);
            return HttpResponse.json(
                url.searchParams.get("afterKey") === "RB-POOL-050"
                    ? [makeRunbookSummary(51)]
                    : firstPage,
            );
        }),
    );
    renderRunbookList(runbookAdmin);

    expect(await screen.findByText("连接池恢复流程 50")).toBeVisible();
    expect(requests[0].searchParams.get("afterKey")).toBe("");
    expect(requests[0].searchParams.get("limit")).toBe("50");
    await user.click(screen.getByRole("button", { name: "加载更多 Runbook" }));

    expect(await screen.findByText("连接池恢复流程 51")).toBeVisible();
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1].searchParams.get("afterKey")).toBe("RB-POOL-050");
});

it("filters unpublished versions from the observer view", async () => {
    server.use(
        http.get("*/api/v1/runbooks", () =>
            HttpResponse.json([
                makeRunbookSummary(1, "published"),
                makeRunbookSummary(2, "draft"),
            ]),
        ),
    );
    renderRunbookList(observer);

    expect(await screen.findByText("连接池恢复流程 1")).toBeVisible();
    expect(screen.queryByText("连接池恢复流程 2")).not.toBeInTheDocument();
    expect(
        screen.queryByRole("link", { name: "新建 Runbook" }),
    ).not.toBeInTheDocument();
    expect(
        screen.getByRole("link", { name: /连接池恢复流程 1/ }),
    ).toHaveAttribute(
        "href",
        `/runbooks/${RUNBOOK_KEY}/versions/${PUBLISHED_ID}`,
    );
});
