import { useAuth, type AuthContextValue } from "./authContext";

// Opaque keys isolate sessions without putting bearer tokens in query caches.
const scopes = new WeakMap<AuthContextValue, string>();

export function useQueryScope(): string {
    const auth = useAuth();
    let scope = scopes.get(auth);
    if (!scope) {
        scope = crypto.randomUUID();
        scopes.set(auth, scope);
    }
    return scope;
}
