import {
    useCallback,
    useEffect,
    useMemo,
    useState,
    type ReactNode,
} from "react";
import type { QueryClient } from "@tanstack/react-query";
import { UserManager, type User } from "oidc-client-ts";

import { queryClient as defaultQueryClient } from "../app/queryClient";
import { AuthContext, type AuthUser, type PlatformRole } from "./authContext";
import { createOidcUserManagerSettings } from "./authConfig";

export type { AuthUser, PlatformRole } from "./authContext";

const roleNames = new Set<PlatformRole>([
    "OBSERVER",
    "ON_CALL_OPERATOR",
    "SRE_APPROVER",
    "RUNBOOK_ADMIN",
    "PLATFORM_ADMIN",
]);

function oidcManager(): UserManager | null {
    if (typeof window === "undefined") {
        return null;
    }
    const settings = createOidcUserManagerSettings(
        {
            authority: import.meta.env.VITE_OIDC_ISSUER,
            clientId: import.meta.env.VITE_OIDC_CLIENT_ID,
            redirectUri: import.meta.env.VITE_OIDC_REDIRECT_URI,
        },
        {
            origin: window.location.origin,
            sessionStorage: window.sessionStorage,
        },
    );
    return settings ? new UserManager(settings) : null;
}

function stringArray(value: unknown): string[] {
    return Array.isArray(value)
        ? value.filter((entry): entry is string => typeof entry === "string")
        : [];
}

function mapUser(user: User | null): AuthUser | null {
    if (!user || user.expired) {
        return null;
    }
    const realmAccess = user.profile.realm_access;
    const roleClaims =
        realmAccess && typeof realmAccess === "object" && "roles" in realmAccess
            ? stringArray(realmAccess.roles)
            : [];
    const roles = roleClaims
        .map((role) => role.toUpperCase())
        .filter((role): role is PlatformRole =>
            roleNames.has(role as PlatformRole),
        );
    return {
        subject: user.profile.sub,
        displayName:
            typeof user.profile.name === "string" && user.profile.name.trim()
                ? user.profile.name
                : user.profile.sub,
        roles,
        serviceIds: stringArray(user.profile.service_ids),
        accessToken: user.access_token,
    };
}

export function AuthProvider({
    children,
    user: suppliedUser,
    queryClient: suppliedQueryClient,
}: {
    children: ReactNode;
    user?: AuthUser | null;
    queryClient?: QueryClient;
}) {
    const manager = useMemo(() => oidcManager(), []);
    const queryClient = suppliedQueryClient ?? defaultQueryClient;
    const [resolvedUser, setResolvedUser] = useState<AuthUser | null>(
        suppliedUser ?? null,
    );
    const [isLoading, setLoading] = useState(() => Boolean(manager));

    useEffect(() => {
        if (suppliedUser !== undefined) {
            return;
        }
        if (!manager) {
            return;
        }

        let active = true;
        const refresh = (next: User | null) => {
            if (active) {
                setResolvedUser(mapUser(next));
                setLoading(false);
            }
        };
        const unload = () => refresh(null);
        const initialize = async () => {
            const callbackParams = new URLSearchParams(window.location.search);
            if (
                window.location.pathname === "/" &&
                callbackParams.has("state")
            ) {
                await manager.signoutRedirectCallback();
                window.history.replaceState({}, document.title, "/incidents");
                refresh(null);
                return;
            }
            if (
                window.location.pathname === "/auth/callback" &&
                (callbackParams.has("code") || callbackParams.has("error"))
            ) {
                const callbackUser = await manager.signinRedirectCallback();
                window.history.replaceState({}, document.title, "/incidents");
                refresh(callbackUser);
                return;
            }
            refresh(await manager.getUser());
        };
        void initialize().catch(() => refresh(null));
        manager.events.addUserLoaded(refresh);
        manager.events.addUserUnloaded(unload);
        return () => {
            active = false;
            manager.events.removeUserLoaded(refresh);
            manager.events.removeUserUnloaded(unload);
        };
    }, [manager, suppliedUser]);

    const signIn = useCallback(async () => {
        if (!manager) {
            throw new Error("OIDC 尚未配置");
        }
        await manager.signinRedirect();
    }, [manager]);

    const signOut = useCallback(async () => {
        queryClient.clear();
        setResolvedUser(null);
        if (!manager) {
            return;
        }
        await manager.signoutRedirect();
    }, [manager, queryClient]);

    const user = suppliedUser !== undefined ? suppliedUser : resolvedUser;
    const loading = suppliedUser !== undefined ? false : isLoading;
    const value = useMemo(
        () => ({ user, isLoading: loading, signIn, signOut }),
        [user, loading, signIn, signOut],
    );
    return (
        <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
    );
}
