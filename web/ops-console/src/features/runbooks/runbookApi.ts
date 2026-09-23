import {
    useInfiniteQuery,
    useMutation,
    useQuery,
    useQueryClient,
} from "@tanstack/react-query";

import { apiFetch } from "../../api/client";
import type { components } from "../../api/generated";
import { useAuth } from "../../auth/authContext";
import { useQueryScope } from "../../auth/useQueryScope";

export type RunbookSummary = components["schemas"]["RunbookSummary"];
export type ServiceSummary = components["schemas"]["ServiceSummary"];
export type VersionView = components["schemas"]["VersionView"];
export type VersionDiff = components["schemas"]["VersionDiff"];
export type DraftInput = components["schemas"]["DraftInput"];
export type DraftContent = components["schemas"]["DraftContent"];
export type RunbookDefinition = components["schemas"]["RunbookDefinition"];

const pageSize = 50;

export const runbookKeys = {
    all: (scope: string) => ["runbooks", scope] as const,
    list: (scope: string) => ["runbooks", scope, "list"] as const,
    services: (scope: string) => ["runbooks", scope, "services"] as const,
    versions: (scope: string, runbookKey: string) =>
        ["runbooks", scope, "versions", runbookKey] as const,
    version: (scope: string, versionId: string) =>
        ["runbooks", scope, "version", versionId] as const,
    diff: (scope: string, beforeVersionId: string, afterVersionId: string) =>
        ["runbooks", scope, "diff", beforeVersionId, afterVersionId] as const,
};

function authorizationHeaders(accessToken?: string): HeadersInit {
    return accessToken ? { Authorization: `Bearer ${accessToken}` } : {};
}

export function idempotencyKey(prefix: string): string {
    const suffix =
        typeof crypto.randomUUID === "function"
            ? crypto.randomUUID()
            : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    return `${prefix}-${suffix}`;
}

function pageQuery(afterKey: string): string {
    return new URLSearchParams({
        afterKey,
        limit: String(pageSize),
    }).toString();
}

export function useRunbooks() {
    const { user } = useAuth();
    const scope = useQueryScope();
    return useInfiniteQuery({
        queryKey: runbookKeys.list(scope),
        initialPageParam: "",
        queryFn: ({ pageParam, signal }) =>
            apiFetch<RunbookSummary[]>(`/runbooks?${pageQuery(pageParam)}`, {
                headers: authorizationHeaders(user?.accessToken),
                signal,
            }),
        getNextPageParam: (lastPage) =>
            lastPage.length === pageSize
                ? lastPage[lastPage.length - 1]?.runbookKey
                : undefined,
        enabled: Boolean(user),
        retry: false,
    });
}

export function useServices(enabled = true) {
    const { user } = useAuth();
    const scope = useQueryScope();
    return useInfiniteQuery({
        queryKey: runbookKeys.services(scope),
        initialPageParam: "",
        queryFn: ({ pageParam, signal }) =>
            apiFetch<ServiceSummary[]>(`/services?${pageQuery(pageParam)}`, {
                headers: authorizationHeaders(user?.accessToken),
                signal,
            }),
        getNextPageParam: (lastPage) =>
            lastPage.length === pageSize
                ? lastPage[lastPage.length - 1]?.serviceKey
                : undefined,
        enabled: Boolean(user) && enabled,
        retry: false,
    });
}

export function useRunbookVersions(runbookKey: string) {
    const { user } = useAuth();
    const scope = useQueryScope();
    return useInfiniteQuery({
        queryKey: runbookKeys.versions(scope, runbookKey),
        initialPageParam: 0,
        queryFn: ({ pageParam, signal }) =>
            apiFetch<VersionView[]>(
                `/runbooks/${encodeURIComponent(runbookKey)}/versions?${new URLSearchParams({ afterVersion: String(pageParam), limit: String(pageSize) })}`,
                { headers: authorizationHeaders(user?.accessToken), signal },
            ),
        getNextPageParam: (lastPage) =>
            lastPage.length === pageSize
                ? lastPage[lastPage.length - 1]?.versionNumber
                : undefined,
        enabled: Boolean(user && runbookKey),
        retry: false,
    });
}

export function useRunbookVersion(versionId: string) {
    const { user } = useAuth();
    const scope = useQueryScope();
    return useQuery({
        queryKey: runbookKeys.version(scope, versionId),
        queryFn: ({ signal }) =>
            apiFetch<VersionView>(`/runbook-versions/${versionId}`, {
                headers: authorizationHeaders(user?.accessToken),
                signal,
            }),
        enabled: Boolean(user && versionId),
        retry: false,
    });
}

export function useRunbookDiff(
    beforeVersionId: string,
    afterVersionId: string,
    enabled = true,
) {
    const { user } = useAuth();
    const scope = useQueryScope();
    return useQuery({
        queryKey: runbookKeys.diff(scope, beforeVersionId, afterVersionId),
        queryFn: ({ signal }) =>
            apiFetch<VersionDiff>(
                `/runbook-versions/${beforeVersionId}/diff?${new URLSearchParams({ otherVersionId: afterVersionId })}`,
                { headers: authorizationHeaders(user?.accessToken), signal },
            ),
        enabled: Boolean(user && beforeVersionId && afterVersionId && enabled),
        retry: false,
    });
}

export function useCreateRunbookDraft(runbookKey: string) {
    const { user } = useAuth();
    const scope = useQueryScope();
    const queryClient = useQueryClient();
    return useMutation({
        mutationKey: ["runbook-create", scope, runbookKey],
        mutationFn: ({
            body,
            idempotencyKey: key,
        }: {
            body: DraftInput;
            idempotencyKey: string;
        }) =>
            apiFetch<VersionView>(
                `/runbooks/${encodeURIComponent(runbookKey)}/versions`,
                {
                    method: "POST",
                    headers: {
                        ...authorizationHeaders(user?.accessToken),
                        "Idempotency-Key": key,
                    },
                    body: JSON.stringify(body),
                },
            ),
        retry: false,
        onSuccess: async (version) => {
            queryClient.setQueryData(
                runbookKeys.version(scope, version.id),
                version,
            );
            await Promise.all([
                queryClient.invalidateQueries({
                    queryKey: runbookKeys.list(scope),
                }),
                queryClient.invalidateQueries({
                    queryKey: runbookKeys.versions(scope, runbookKey),
                }),
            ]);
        },
    });
}

export function useUpdateRunbookDraft(runbookKey: string, versionId: string) {
    const { user } = useAuth();
    const scope = useQueryScope();
    const queryClient = useQueryClient();
    return useMutation({
        mutationKey: ["runbook-update", scope, versionId],
        mutationFn: ({
            body,
            revision,
            idempotencyKey: key,
        }: {
            body: DraftContent;
            revision: number;
            idempotencyKey: string;
        }) =>
            apiFetch<VersionView>(`/runbook-versions/${versionId}`, {
                method: "PUT",
                headers: {
                    ...authorizationHeaders(user?.accessToken),
                    "If-Match": `"${revision}"`,
                    "Idempotency-Key": key,
                },
                body: JSON.stringify(body),
            }),
        retry: false,
        onSuccess: async (version) => {
            queryClient.setQueryData(
                runbookKeys.version(scope, versionId),
                version,
            );
            await Promise.all([
                queryClient.invalidateQueries({
                    queryKey: runbookKeys.list(scope),
                }),
                queryClient.invalidateQueries({
                    queryKey: runbookKeys.versions(scope, runbookKey),
                }),
            ]);
        },
    });
}

export function useReviewRunbookVersion(runbookKey: string, versionId: string) {
    const { user } = useAuth();
    const scope = useQueryScope();
    const queryClient = useQueryClient();
    return useMutation({
        mutationKey: ["runbook-review", scope, versionId],
        mutationFn: ({
            revision,
            idempotencyKey: key,
        }: {
            revision: number;
            idempotencyKey: string;
        }) =>
            apiFetch<VersionView>(`/runbook-versions/${versionId}/review`, {
                method: "POST",
                headers: {
                    ...authorizationHeaders(user?.accessToken),
                    "If-Match": `"${revision}"`,
                    "Idempotency-Key": key,
                },
            }),
        retry: false,
        onSuccess: async (version) => {
            queryClient.setQueryData(
                runbookKeys.version(scope, versionId),
                version,
            );
            await queryClient.invalidateQueries({
                queryKey: runbookKeys.versions(scope, runbookKey),
            });
        },
    });
}

export function usePublishRunbookVersion(
    runbookKey: string,
    versionId: string,
) {
    const { user } = useAuth();
    const scope = useQueryScope();
    const queryClient = useQueryClient();
    return useMutation({
        mutationKey: ["runbook-publish", scope, versionId],
        mutationFn: ({
            revision,
            idempotencyKey: key,
        }: {
            revision: number;
            idempotencyKey: string;
        }) =>
            apiFetch<VersionView>(`/runbook-versions/${versionId}/publish`, {
                method: "POST",
                headers: {
                    ...authorizationHeaders(user?.accessToken),
                    "If-Match": `"${revision}"`,
                    "Idempotency-Key": key,
                },
            }),
        retry: false,
        onSuccess: async (version) => {
            queryClient.setQueryData(
                runbookKeys.version(scope, versionId),
                version,
            );
            await Promise.all([
                queryClient.invalidateQueries({
                    queryKey: runbookKeys.list(scope),
                }),
                queryClient.invalidateQueries({
                    queryKey: runbookKeys.versions(scope, runbookKey),
                }),
            ]);
        },
    });
}
