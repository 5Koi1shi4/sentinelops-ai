import {
    useCallback,
    useEffect,
    useMemo,
    useState,
    type ReactNode,
} from "react";
import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";

import { AuthContext, type AuthUser, type PlatformRole } from "./authContext";

export type { AuthUser, PlatformRole } from "./authContext";

const roleNames = new Set<PlatformRole>([
    "OBSERVER",
    "ON_CALL_OPERATOR",
    "SRE_APPROVER",
    "RUNBOOK_ADMIN",
    "PLATFORM_ADMIN",
]);

function oidcManager(): UserManager | null {
    const authority = import.meta.env.VITE_OIDC_ISSUER as string | undefined;
    const clientId = import.meta.env.VITE_OIDC_CLIENT_ID as string | undefined;
    if (!authority || !clientId || typeof window === "undefined") {
        return null;
    }
    return new UserManager({
        authority,
        client_id: clientId,
        redirect_uri:
            (import.meta.env.VITE_OIDC_REDIRECT_URI as string | undefined) ??
            `${window.location.origin}/auth/callback`,
        post_logout_redirect_uri: window.location.origin,
        response_type: "code",
        scope: "openid profile email",
        userStore: new WebStorageStateStore({ store: window.sessionStorage }),
        automaticSilentRenew: true,
    });
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
}: {
    children: ReactNode;
    user?: AuthUser | null;
}) {
    const manager = useMemo(() => oidcManager(), []);
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
            if (
                window.location.pathname === "/auth/callback" &&
                new URLSearchParams(window.location.search).has("code")
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
        if (!manager) {
            setResolvedUser(null);
            return;
        }
        await manager.signoutRedirect();
    }, [manager]);

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
