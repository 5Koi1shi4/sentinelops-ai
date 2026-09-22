import { useInfiniteQuery, useQuery } from "@tanstack/react-query";

import { ApiProblem, apiFetch } from "../../api/client";
import type { components } from "../../api/generated";
import { useAuth } from "../../auth/authContext";
import { useQueryScope } from "../../auth/useQueryScope";

export type EvalRunRequest = components["schemas"]["EvalRunRequest"];
export type EvalRunView = components["schemas"]["EvalRunView"];
export type EvalRunSummary = components["schemas"]["EvalRunSummary"];
export type EvalAggregateMetrics =
    components["schemas"]["EvalAggregateMetrics"];

export interface EvalTrendPoint {
    id: string;
    startedAt: string;
    accuracy: number | null;
    latencyMs: number | null;
    costUsd: number | null;
}

export const EVAL_DATASET_KEY = "incidents-v1";
export const EVAL_HISTORY_LIMIT = 20;

function authorizationHeaders(accessToken?: string): HeadersInit {
    return accessToken ? { Authorization: `Bearer ${accessToken}` } : {};
}

function evalHistoryUrl(beforeId?: string): string {
    const parameters = new URLSearchParams();
    if (beforeId) {
        parameters.set("beforeId", beforeId);
    }
    parameters.set("limit", String(EVAL_HISTORY_LIMIT));
    return `/eval-runs?${parameters.toString()}`;
}

export function useEvalRunHistory(enabled: boolean) {
    const { user } = useAuth();
    const queryScope = useQueryScope();

    return useInfiniteQuery({
        queryKey: ["eval-run-history", queryScope],
        queryFn: ({ pageParam, signal }) =>
            apiFetch<EvalRunSummary[]>(evalHistoryUrl(pageParam), {
                headers: authorizationHeaders(user?.accessToken),
                signal,
            }),
        initialPageParam: undefined as string | undefined,
        getNextPageParam: (lastPage) =>
            lastPage.length === EVAL_HISTORY_LIMIT
                ? lastPage[lastPage.length - 1]?.id
                : undefined,
        enabled,
        staleTime: 2_000,
        refetchInterval: (query) => {
            const hasRunningRun = query.state.data?.pages.some((page) =>
                page.some((run) => run.status.toLowerCase() === "running"),
            );
            return hasRunningRun ? 2_000 : false;
        },
        refetchIntervalInBackground: false,
    });
}

export function useEvalRun(runId: string | null, enabled: boolean) {
    const { user } = useAuth();
    const queryScope = useQueryScope();

    return useQuery({
        queryKey: ["eval-run", queryScope, runId],
        queryFn: ({ signal }) => {
            if (!runId) {
                throw new Error("An Eval run ID is required");
            }
            return apiFetch<EvalRunView>(`/eval-runs/${runId}`, {
                headers: authorizationHeaders(user?.accessToken),
                signal,
            });
        },
        enabled: enabled && Boolean(runId),
        refetchInterval: (query) =>
            query.state.data?.status === "running" ? 2_000 : false,
        refetchIntervalInBackground: false,
    });
}

export async function createEvalRun({
    body,
    idempotencyKey,
    accessToken,
}: {
    body: EvalRunRequest;
    idempotencyKey: string;
    accessToken?: string;
}): Promise<EvalRunView> {
    return apiFetch<EvalRunView>("/eval-runs", {
        method: "POST",
        headers: {
            ...authorizationHeaders(accessToken),
            "Idempotency-Key": idempotencyKey,
        },
        body: JSON.stringify(body),
    });
}

export function apiProblemStatus(error: unknown): number | null {
    return error instanceof ApiProblem ? error.status : null;
}
