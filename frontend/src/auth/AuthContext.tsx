import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import * as authApi from "../api/auth";
import { setAccessToken, setRefreshHandler } from "../api/client";
import type { Athlete } from "../api/types";

const REFRESH_TOKEN_KEY = "cadence.refresh_token";

interface AuthContextValue {
  user: Athlete | null;
  /** Undetermined until the initial silent-refresh attempt (if any) finishes. */
  isLoading: boolean;
  register: (name: string, email: string, password: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  /** Updates the cached profile after an edit (e.g. PATCH /v1/athletes/{id}) without a full reload. */
  setUser: (athlete: Athlete) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Athlete | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const logout = useCallback(() => {
    setAccessToken(null);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    setUser(null);
  }, []);

  const applyAuthResponse = useCallback((athlete: Athlete, tokens: { access_token: string; refresh_token: string }) => {
    setAccessToken(tokens.access_token);
    localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refresh_token);
    setUser(athlete);
  }, []);

  const refresh = useCallback(async (): Promise<string | null> => {
    const storedRefreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
    if (!storedRefreshToken) {
      return null;
    }
    try {
      const tokens = await authApi.refreshTokens(storedRefreshToken);
      setAccessToken(tokens.access_token);
      localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refresh_token);
      return tokens.access_token;
    }
    catch {
      logout();
      return null;
    }
  }, [logout]);

  useEffect(() => {
    setRefreshHandler(refresh);
    return () => setRefreshHandler(null);
  }, [refresh]);

  useEffect(() => {
    (async () => {
      const newAccessToken = await refresh();
      if (newAccessToken) {
        try {
          setUser(await authApi.getMe());
        }
        catch {
          logout();
        }
      }
      setIsLoading(false);
    })();
    // Only run on mount - refresh/logout are stable via useCallback.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const register = useCallback(async (name: string, email: string, password: string) => {
    const response = await authApi.register(name, email, password);
    applyAuthResponse(response.athlete, response.tokens);
  }, [applyAuthResponse]);

  const login = useCallback(async (email: string, password: string) => {
    const response = await authApi.login(email, password);
    applyAuthResponse(response.athlete, response.tokens);
  }, [applyAuthResponse]);

  const value = useMemo(
    () => ({ user, isLoading, register, login, logout, setUser }),
    [user, isLoading, register, login, logout, setUser],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components -- the hook belongs with its context, not in its own file
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider.");
  }
  return context;
}
