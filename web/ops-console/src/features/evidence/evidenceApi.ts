import { useQuery } from "@tanstack/react-query";

import { apiFetch } from "../../api/client";
import type { components } from "../../api/generated";
import { useAuth } from "../../auth/authContext";
import { useQueryScope } from "../../auth/useQueryScope";

export type EvidenceSnapshot = components["schemas"]["EvidenceSnapshot"];

export function useEvidenceSnapshot(
    incidentId: string,
    evidenceId: string,
) {
    const { user } = useAuth();
    const scope = useQueryScope();
    return useQuery({
        queryKey: ["evidence-snapshot", scope, incidentId, evidenceId],
        queryFn: ({ signal }) =>
            apiFetch<EvidenceSnapshot>(
                `/incidents/${encodeURIComponent(incidentId)}/evidence/${encodeURIComponent(evidenceId)}`,
                {
                    signal,
                    headers: user?.accessToken
                        ? { Authorization: `Bearer ${user.accessToken}` }
                        : undefined,
                },
            ),
        enabled: Boolean(user),
        retry: false,
        gcTime: 0,
    });
}
