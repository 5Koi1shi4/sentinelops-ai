import { createContext, useContext } from "react";

import type { components } from "../api/generated";

export type PlatformRole = components["schemas"]["PlatformRole"];

export interface AuthUser {
    subject: string;
    displayName: string;
    roles: PlatformRole[];
    serviceIds: string[];
    accessToken?: string;
}

export interface AuthContextValue {
    user: AuthUser | null;
    isLoading: boolean;
    signIn: () => Promise<void>;
    signOut: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
    const value = useContext(AuthContext);
    if (!value) {
        throw new Error("useAuth must be used inside AuthProvider");
    }
    return value;
}
