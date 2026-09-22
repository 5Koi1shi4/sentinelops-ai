import type { ReactNode } from "react";

import { type PlatformRole, useAuth } from "./authContext";

export function RequireRole({
    anyOf,
    children,
    fallback = null,
}: {
    anyOf: PlatformRole[];
    children: ReactNode;
    fallback?: ReactNode;
}) {
    const { user } = useAuth();
    const permitted = user?.roles.some(
        (role) => role === "PLATFORM_ADMIN" || anyOf.includes(role),
    );
    return permitted ? children : fallback;
}
