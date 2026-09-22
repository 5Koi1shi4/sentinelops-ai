import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { AuthProvider, type AuthUser } from "../../auth/AuthProvider";
import { server, EVIDENCE_ID, INCIDENT_ID } from "../../mocks/handlers";
import { EvidenceDrawer } from "./EvidenceDrawer";

const observer: AuthUser = {
    subject: "reader",
    displayName: "值班观察员",
    roles: ["OBSERVER"],
    serviceIds: ["service-a"],
    accessToken: "test-session-a",
};
const reference = {
    id: EVIDENCE_ID,
    sourceType: "LOKI",
    sourceRef: "checkout-errors",
    contentHash: "a".repeat(64),
    capturedAt: "2026-09-23T01:00:00Z",
    truncated: true,
};
const snapshot = {
    ...reference,
    from: "2026-09-23T00:45:00Z",
    to: "2026-09-23T01:00:00Z",
    redactionCount: 2,
    redactionRules: ["authorization", "password"],
    redactedPayload: {
        message: "<script>alert('untrusted')</script>",
        token: "[REDACTED]",
    },
};
const path = `/api/v1/incidents/${INCIDENT_ID}/evidence/${EVIDENCE_ID}`;

function setup(user = observer) {
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    const tree = (identity: AuthUser) => (
        <QueryClientProvider client={client}>
            <AuthProvider user={identity}>
                <EvidenceDrawer incidentId={INCIDENT_ID} evidence={reference} />
            </AuthProvider>
        </QueryClientProvider>
    );
    return { ...render(tree(user)), tree, client };
}

it("fetches only the selected snapshot on demand and renders safe metadata and escaped content", async () => {
    let requests = 0;
    let authorization: string | null = null;
    server.use(
        http.get(path, ({ request }) => {
            requests++;
            authorization = request.headers.get("Authorization");
            return HttpResponse.json(snapshot);
        }),
    );
    const { client } = setup();
    expect(requests).toBe(0);
    await userEvent.click(
        screen.getByRole("button", { name: "查看 checkout-errors" }),
    );
    expect(await screen.findByText("authorization、password")).toBeVisible();
    expect(screen.getByText(/已截断/)).toBeVisible();
    expect(screen.getByText("2026-09-23T00:45:00Z")).toBeVisible();
    expect(screen.getByText("a".repeat(64))).toBeVisible();
    expect(screen.getByText(/<script>alert/)).toBeVisible();
    expect(document.querySelector("script")).toBeNull();
    expect(authorization).toBe("Bearer test-session-a");
    expect(requests).toBe(1);
    expect(
        JSON.stringify(
            client
                .getQueryCache()
                .getAll()
                .map((query) => query.queryKey),
        ),
    ).not.toContain("test-session-a");
});

it("discards visible cached evidence when the same subject receives a different token or scope", async () => {
    server.use(
        http.get(path, ({ request }) =>
            request.headers.get("Authorization") === "Bearer test-session-a"
                ? HttpResponse.json(snapshot)
                : HttpResponse.json({ title: "Forbidden" }, { status: 403 }),
        ),
    );
    const view = setup();
    await userEvent.click(
        screen.getByRole("button", { name: "查看 checkout-errors" }),
    );
    expect(await screen.findByText(/<script>alert/)).toBeVisible();
    view.rerender(
        view.tree({
            ...observer,
            serviceIds: [],
            accessToken: "test-session-b",
        }),
    );
    expect(screen.queryByText(/<script>alert/)).not.toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent(
        "无权读取此证据",
    );
});

it("shows unavailable historical windows and restores focus after Escape", async () => {
    server.use(
        http.get(path, () =>
            HttpResponse.json({
                ...snapshot,
                from: null,
                to: null,
                truncated: false,
                redactionCount: 0,
                redactionRules: [],
            }),
        ),
    );
    const { client } = setup();
    const user = userEvent.setup();
    const trigger = screen.getByRole("button", {
        name: "查看 checkout-errors",
    });
    await user.click(trigger);
    expect(await screen.findByText("历史快照未记录查询时间范围")).toBeVisible();
    await user.keyboard("{Escape}");
    await waitFor(() =>
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(trigger).toHaveFocus();
    await waitFor(() =>
        expect(client.getQueryCache().getAll()).toHaveLength(0),
    );
});

it("reports a missing snapshot without showing content", async () => {
    server.use(
        http.get(path, () =>
            HttpResponse.json({ title: "Not found" }, { status: 404 }),
        ),
    );
    setup();
    await userEvent.click(
        screen.getByRole("button", { name: "查看 checkout-errors" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
        "证据不存在或已不可用",
    );
    expect(screen.queryByText(/<script>alert/)).not.toBeInTheDocument();
});
