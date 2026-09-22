import {
    useMutation,
    useQuery,
    useQueryClient,
    type QueryClient,
} from "@tanstack/react-query";

import { ApiProblem, apiFetch } from "../../api/client";
import type { components } from "../../api/generated";
import { useAuth } from "../../auth/authContext";

export type IncidentCockpit = components["schemas"]["IncidentCockpit"];
export type IncidentSummary = components["schemas"]["IncidentSummary"];
export type IncidentPage = components["schemas"]["IncidentPage"];
export type IncidentTimelinePage =
    components["schemas"]["IncidentTimelinePage"];
export type CockpitDiagnosis = components["schemas"]["CockpitDiagnosis"];
export type CockpitApproval = components["schemas"]["CockpitApproval"];
export type CockpitExecution = components["schemas"]["CockpitExecution"];
export type EvidenceReference = components["schemas"]["EvidenceReference"];
export type ApprovalDecision = components["schemas"]["ApprovalDecision"];

export interface IncidentFilters {
    status?: components["schemas"]["IncidentStatus"];
    serviceId?: string;
    severity?: components["schemas"]["Severity"];
}

const terminalStatuses = new Set(["RESOLVED", "SUPPRESSED"]);

function queryString(filters: IncidentFilters): string {
    const parameters = new URLSearchParams();
    Object.entries(filters).forEach(([key, value]) => {
        if (value) {
            parameters.set(key, value);
        }
    });
    const value = parameters.toString();
    return value ? `?${value}` : "";
}

function idempotencyKey(prefix: string): string {
    const suffix =
        typeof crypto.randomUUID === "function"
            ? crypto.randomUUID()
            : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    return `${prefix}-${suffix}`;
}

function authorizationHeaders(accessToken?: string): HeadersInit {
    return accessToken ? { Authorization: `Bearer ${accessToken}` } : {};
}

function commandHeaders(
    version: number,
    prefix: string,
    accessToken?: string,
): HeadersInit {
    return {
        ...authorizationHeaders(accessToken),
        "If-Match": `"${version}"`,
        "Idempotency-Key": idempotencyKey(prefix),
    };
}

async function refreshAfterStale(
    error: unknown,
    queryClient: QueryClient,
    incidentId: string,
) {
    if (error instanceof ApiProblem && error.status === 412) {
        await Promise.all([
            queryClient.invalidateQueries({
                queryKey: ["incident", incidentId],
            }),
            queryClient.invalidateQueries({
                queryKey: ["incidentTimeline", incidentId],
            }),
        ]);
    }
}

export function staleMessage(error: unknown): string | null {
    return error instanceof ApiProblem && error.status === 412
        ? "事故已更新，请重新检查建议"
        : null;
}

export function useIncidents(filters: IncidentFilters) {
    const { user } = useAuth();
    return useQuery({
        queryKey: ["incidents", filters, user?.subject],
        queryFn: () =>
            apiFetch<IncidentPage>(`/incidents${queryString(filters)}`, {
                headers: authorizationHeaders(user?.accessToken),
            }),
        refetchInterval: 5_000,
    });
}

export function useIncident(incidentId: string) {
    const { user } = useAuth();
    return useQuery({
        queryKey: ["incident", incidentId, user?.subject],
        queryFn: () =>
            apiFetch<IncidentCockpit>(`/incidents/${incidentId}`, {
                headers: authorizationHeaders(user?.accessToken),
            }),
        enabled: Boolean(incidentId),
        refetchInterval: (query) => {
            const status = query.state.data?.incident.status;
            return status && !terminalStatuses.has(status) ? 5_000 : false;
        },
    });
}

export function useIncidentTimeline(incidentId: string) {
    const { user } = useAuth();
    return useQuery({
        queryKey: ["incidentTimeline", incidentId, user?.subject],
        queryFn: () =>
            apiFetch<IncidentTimelinePage>(
                `/incidents/${incidentId}/timeline`,
                {
                    headers: authorizationHeaders(user?.accessToken),
                },
            ),
        enabled: Boolean(incidentId),
    });
}

export function useStartDiagnosis(incidentId: string) {
    const queryClient = useQueryClient();
    const { user } = useAuth();
    return useMutation({
        mutationKey: ["diagnose", incidentId],
        mutationFn: ({ version }: { version: number }) =>
            apiFetch<components["schemas"]["DiagnosisProposal"]>(
                `/incidents/${incidentId}/diagnosis-runs`,
                {
                    method: "POST",
                    headers: commandHeaders(
                        version,
                        "diagnosis",
                        user?.accessToken,
                    ),
                },
            ),
        onSuccess: async () => {
            await queryClient.invalidateQueries({
                queryKey: ["incident", incidentId],
            });
        },
        onError: (error) => refreshAfterStale(error, queryClient, incidentId),
    });
}

export function useRequestApproval(incidentId: string) {
    const queryClient = useQueryClient();
    const { user } = useAuth();
    return useMutation({
        mutationKey: ["requestApproval", incidentId],
        mutationFn: ({
            proposalId,
            version,
        }: {
            proposalId: string;
            version: number;
        }) =>
            apiFetch<components["schemas"]["ApprovalCommandResult"]>(
                `/incidents/${incidentId}/approval-requests`,
                {
                    method: "POST",
                    headers: commandHeaders(
                        version,
                        "approval-request",
                        user?.accessToken,
                    ),
                    body: JSON.stringify({ proposalId }),
                },
            ),
        onSuccess: async () => {
            await queryClient.invalidateQueries({
                queryKey: ["incident", incidentId],
            });
        },
        onError: (error) => refreshAfterStale(error, queryClient, incidentId),
    });
}

export function useDecideApproval(incidentId: string, requestId: string) {
    const queryClient = useQueryClient();
    const { user } = useAuth();
    return useMutation({
        mutationKey: ["decideApproval", requestId],
        mutationFn: ({
            decision,
            comment,
            proposalHash,
            version,
        }: {
            decision: ApprovalDecision;
            comment: string;
            proposalHash: string;
            version: number;
        }) =>
            apiFetch<components["schemas"]["ApprovalCommandResult"]>(
                `/approval-requests/${requestId}/decisions`,
                {
                    method: "POST",
                    headers: commandHeaders(
                        version,
                        "approval",
                        user?.accessToken,
                    ),
                    body: JSON.stringify({ decision, comment, proposalHash }),
                },
            ),
        onSuccess: async () => {
            await Promise.all([
                queryClient.invalidateQueries({
                    queryKey: ["incident", incidentId],
                }),
                queryClient.invalidateQueries({
                    queryKey: ["incidentTimeline", incidentId],
                }),
            ]);
        },
        onError: (error) => refreshAfterStale(error, queryClient, incidentId),
    });
}

export function useCreateExecution(incidentId: string) {
    const queryClient = useQueryClient();
    const { user } = useAuth();
    return useMutation({
        mutationKey: ["createExecution", incidentId],
        mutationFn: ({
            proposalId,
            version,
        }: {
            proposalId: string;
            version: number;
        }) =>
            apiFetch<components["schemas"]["ExecutionCommandResult"]>(
                `/incidents/${incidentId}/executions`,
                {
                    method: "POST",
                    headers: commandHeaders(
                        version,
                        "execution",
                        user?.accessToken,
                    ),
                    body: JSON.stringify({ proposalId }),
                },
            ),
        onSuccess: async () => {
            await Promise.all([
                queryClient.invalidateQueries({
                    queryKey: ["incident", incidentId],
                }),
                queryClient.invalidateQueries({
                    queryKey: ["incidentTimeline", incidentId],
                }),
            ]);
        },
        onError: (error) => refreshAfterStale(error, queryClient, incidentId),
    });
}
