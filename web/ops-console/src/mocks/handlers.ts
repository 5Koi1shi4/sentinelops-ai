import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

import type { components } from "../api/generated";

export type IncidentCockpit = components["schemas"]["IncidentCockpit"];

export const INCIDENT_ID = "0199a90a-9c00-7000-8000-000000000001";
export const PROPOSAL_ID = "0199a90a-9c00-7000-8000-000000000002";
export const APPROVAL_ID = "0199a90a-9c00-7000-8000-000000000003";
export const EVIDENCE_ID = "0199a90a-9c00-7000-8000-000000000012";

export const capturedRequests: {
    approvalRequest: Request | null;
    approvalDecision: Request | null;
    execution: Request | null;
} = {
    approvalRequest: null,
    approvalDecision: null,
    execution: null,
};

export function createCockpit(
    overrides: Partial<IncidentCockpit> = {},
): IncidentCockpit {
    const now = Date.now();
    const base: IncidentCockpit = {
        incident: {
            id: INCIDENT_ID,
            serviceId: "0199a90a-9c00-7000-8000-000000000010",
            serviceKey: "checkout-api",
            title: "Checkout API 数据库连接池耗尽",
            severity: "sev1",
            status: "AWAITING_APPROVAL",
            version: 7,
            occurrenceCount: 4,
            openedAt: new Date(now - 12 * 60_000).toISOString(),
            updatedAt: new Date(now - 30_000).toISOString(),
            resolvedAt: null,
        },
        diagnosis: {
            id: PROPOSAL_ID,
            diagnosisRunId: "0199a90a-9c00-7000-8000-000000000020",
            incidentVersion: 2,
            summary: "数据库连接池耗尽",
            hypotheses: [
                {
                    rank: 1,
                    statement: "连接池等待线程与获取超时同步上升",
                    confidence: 0.91,
                    evidenceRefs: [EVIDENCE_ID],
                },
            ],
            missingEvidence: [],
            runbookVersionId: "0199a90a-9c00-7000-8000-000000000030",
            runbookVersion: 1,
            runbookKey: "RB-DB-POOL-03",
            runbookName: "恢复 Checkout 数据库连接池",
            parameters: { replicas: 1 },
            riskLevel: "R1",
            expectedVerification: {
                probe: "demo-http",
                successThreshold: 1,
                attempts: 3,
                intervalSeconds: 2,
            },
            proposalHash: "mKw4vFjWHx9fcYw55mrA6r7gJ0VJRF7fuTxp9kmr0pM",
            createdAt: new Date(now - 9 * 60_000).toISOString(),
        },
        evidence: [
            {
                id: EVIDENCE_ID,
                sourceType: "metric",
                sourceRef: "E-12",
                contentHash: "sha256:evidence-12",
                capturedAt: new Date(now - 10 * 60_000).toISOString(),
                truncated: false,
            },
        ],
        activeApproval: {
            id: APPROVAL_ID,
            proposalId: PROPOSAL_ID,
            proposalHash: "mKw4vFjWHx9fcYw55mrA6r7gJ0VJRF7fuTxp9kmr0pM",
            targetAlias: "demo-checkout",
            status: "PENDING",
            resourceVersion: 0,
            requiredApprovals: 1,
            approvals: 0,
            rejections: 0,
            requesterId: "0199a90a-9c00-7000-8000-000000000040",
            requesterSubject: "operator-demo",
            requesterDisplayName: "演示值班工程师",
            independentApproverRequired: true,
            createdAt: new Date(now - 60_000).toISOString(),
            expiresAt: new Date(now + 8 * 60_000).toISOString(),
            decidedAt: null,
        },
        latestExecution: null,
    };
    return { ...base, ...overrides };
}

let cockpit = createCockpit();

export function setMockCockpit(next: IncidentCockpit) {
    cockpit = next;
}

export function resetMockApi() {
    cockpit = createCockpit();
    capturedRequests.approvalRequest = null;
    capturedRequests.approvalDecision = null;
    capturedRequests.execution = null;
}

export const handlers = [
    http.get("/api/v1/incidents", () =>
        HttpResponse.json({ items: [cockpit.incident], nextCursor: null }),
    ),
    http.get("/api/v1/incidents/:incidentId", () =>
        HttpResponse.json(cockpit, {
            headers: { ETag: `"${cockpit.incident.version}"` },
        }),
    ),
    http.get("/api/v1/incidents/:incidentId/timeline", () =>
        HttpResponse.json({
            items: [
                {
                    id: "0199a90a-9c00-7000-8000-000000000050",
                    sequence: 1,
                    eventType: "diagnosis_recorded",
                    actorType: "user",
                    actorId: "operator-demo",
                    source: null,
                    sourceEventId: null,
                    summary: "数据库连接池耗尽",
                    traceId: "trace-demo-01",
                    evidenceIds: [EVIDENCE_ID],
                    occurredAt: new Date(Date.now() - 9 * 60_000).toISOString(),
                },
            ],
            nextCursor: null,
        }),
    ),
    http.post(
        "/api/v1/incidents/:incidentId/approval-requests",
        async ({ request }) => {
            capturedRequests.approvalRequest = request.clone();
            return HttpResponse.json(
                {
                    id: APPROVAL_ID,
                    incidentId: INCIDENT_ID,
                    proposalId: PROPOSAL_ID,
                    proposalHash: "mKw4vFjWHx9fcYw55mrA6r7gJ0VJRF7fuTxp9kmr0pM",
                    targetAlias: "demo-checkout",
                    status: "PENDING",
                    resourceVersion: 0,
                    requiredApprovals: 1,
                    approvals: 0,
                    rejections: 0,
                    incidentVersion: 8,
                    expiresAt: new Date(Date.now() + 8 * 60_000).toISOString(),
                },
                { status: 201, headers: { ETag: '"8"' } },
            );
        },
    ),
    http.post(
        "/api/v1/approval-requests/:requestId/decisions",
        async ({ request }) => {
            capturedRequests.approvalDecision = request.clone();
            return HttpResponse.json({
                id: APPROVAL_ID,
                incidentId: INCIDENT_ID,
                proposalId: PROPOSAL_ID,
                proposalHash: "mKw4vFjWHx9fcYw55mrA6r7gJ0VJRF7fuTxp9kmr0pM",
                targetAlias: "demo-checkout",
                status: "APPROVED",
                resourceVersion: 1,
                requiredApprovals: 1,
                approvals: 1,
                rejections: 0,
                incidentVersion: 8,
                expiresAt: new Date(Date.now() + 8 * 60_000).toISOString(),
            });
        },
    ),
    http.post(
        "/api/v1/incidents/:incidentId/executions",
        async ({ request }) => {
            capturedRequests.execution = request.clone();
            return HttpResponse.json(
                {
                    id: "0199a90a-9c00-7000-8000-000000000060",
                    incidentId: INCIDENT_ID,
                    proposalId: PROPOSAL_ID,
                    approvalRequestId: APPROVAL_ID,
                    status: "PENDING",
                    fencingToken: 0,
                    incidentVersion: 9,
                    createdAt: new Date().toISOString(),
                },
                { status: 201, headers: { ETag: '"9"' } },
            );
        },
    ),
];

export const server = setupServer(...handlers);
