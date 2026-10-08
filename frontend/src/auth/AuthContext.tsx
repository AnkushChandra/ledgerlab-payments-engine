import { useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { apiRequest, configureApiClient } from '../api/client';
import type { LoginResponse, Role, Session } from '../api/types';

/**
 * The access token lives in sessionStorage so a page refresh keeps the session while closing the
 * tab ends it. Trade-off: sessionStorage is readable by injected scripts, so the app relies on
 * React's escaping and a strict backend CORS policy; see docs/architecture.md.
 */
const STORAGE_KEY = 'ledgerlab.session';
const ROLE_RANK: Record<Role, number> = { VIEWER: 0, OPERATIONS: 1, ADMIN: 2 };

interface StoredSession {
  token: string;
  expiresAt: number;
  session: Session;
}

interface AuthContextValue {
  session: Session | null;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  can: (role: Role) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function readStored(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const stored = JSON.parse(raw) as StoredSession;
    return stored.expiresAt > Date.now() ? stored : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [stored, setStored] = useState<StoredSession | null>(readStored);

  const logout = useCallback(() => {
    sessionStorage.removeItem(STORAGE_KEY);
    setStored(null);
    queryClient.clear();
  }, [queryClient]);

  useEffect(() => {
    configureApiClient(() => stored?.token ?? null, logout);
  }, [stored, logout]);

  useEffect(() => {
    if (!stored) {
      return;
    }
    const timer = window.setTimeout(logout, Math.max(0, stored.expiresAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [stored, logout]);

  const login = useCallback(
    async (email: string, password: string) => {
      const response = await apiRequest<LoginResponse>('/auth/login', {
        method: 'POST',
        body: { email, password },
      });
      const next: StoredSession = {
        token: response.accessToken,
        expiresAt: Date.now() + response.expiresInSeconds * 1000,
        session: response.session,
      };
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      configureApiClient(() => next.token, logout);
      setStored(next);
    },
    [logout],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      session: stored?.session ?? null,
      login,
      logout,
      can: (role: Role) => (stored ? ROLE_RANK[stored.session.role] >= ROLE_RANK[role] : false),
    }),
    [stored, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
