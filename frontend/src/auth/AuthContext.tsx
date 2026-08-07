import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import * as authApi from "../api/auth";
import * as athletesApi from "../api/athletes";
import { setAccessToken, setRefreshHandler } from "../api/client";
import type { Athlete } from "../api/types";

const REFRESH_TOKEN_KEY = "cadence.refresh_token";

interface AuthContextValue {
  /** The currently-active profile - the signed-in principal's own, unless viewing as a
   * coached athlete (see activeAthleteId), in which case this is that athlete's profile. */
  user: Athlete | null;
  /** The signed-in principal's own id, stable across a context switch - unlike `user.id`,
   * which becomes the viewed athlete's id while viewing as them. Server-attributed actions
   * (e.g. an ActivityComment's author_id) are always the real principal, never the
   * delegated athlete_id, so compare against this - not `user.id` - to tell whether such a
   * record was authored by whoever is actually signed in right now. */
  selfId: string | null;
  /** Non-null while viewing as a coached athlete rather than the signed-in principal.
   * Unlike `user.is_coach`, this and `isCoachAccount` are stable across a context switch -
   * they describe who's really logged in, not whichever profile is currently active. */
  activeAthleteId: string | null;
  /** Whether the signed-in principal (not the active profile) has coaching relationships -
   * use this, not `user.is_coach`, to decide whether to show the context switcher, since
   * `user` itself changes while viewing as someone else. */
  isCoachAccount: boolean;
  /** Whether the signed-in principal (not the active profile) is a platform admin - same
   * "pinned to the real principal, not the active profile" rule as isCoachAccount. */
  isAdminAccount: boolean;
  /** Undetermined until the initial silent-refresh attempt (if any) finishes. */
  isLoading: boolean;
  register: (name: string, email: string, password: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  /** Updates the cached profile after an edit (e.g. PATCH /v1/athletes/{id}) without a full reload. */
  setUser: (athlete: Athlete) => void;
  /** Switches to viewing as a coached athlete - mints a delegated JWT (athlete_id set,
   * sub unchanged) so every athlete-scoped screen transparently acts on their data. */
  switchToAthlete: (athleteId: string) => Promise<void>;
  /** Switches back to the signed-in principal's own data. */
  switchToSelf: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Athlete | null>(null);
  const [selfId, setSelfId] = useState<string | null>(null);
  const [activeAthleteId, setActiveAthleteId] = useState<string | null>(null);
  const [isCoachAccount, setIsCoachAccount] = useState(false);
  const [isAdminAccount, setIsAdminAccount] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const queryClient = useQueryClient();

  const logout = useCallback(() => {
    setAccessToken(null);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    setUser(null);
    setSelfId(null);
    setActiveAthleteId(null);
    setIsCoachAccount(false);
    setIsAdminAccount(false);
  }, []);

  const applyAuthResponse = useCallback((athlete: Athlete, tokens: { access_token: string; refresh_token: string }) => {
    setAccessToken(tokens.access_token);
    localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refresh_token);
    setUser(athlete);
    setSelfId(athlete.id);
    setActiveAthleteId(null);
    setIsCoachAccount(athlete.is_coach);
    setIsAdminAccount(athlete.is_admin);
  }, []);

  // The backend rotates refresh tokens on use (single-use), so two concurrent callers
  // presenting the same stored token race: whichever request the server sees second gets
  // rejected outright, and its caller would otherwise call logout() and wipe out the state
  // the first (successful) call just set. This happens for real - React StrictMode
  // double-invokes the mount effect below in dev, and any burst of API calls that all 401
  // at once each independently call this via setRefreshHandler. A single in-flight promise,
  // shared via a ref (so it survives StrictMode's simulated remount of the same instance),
  // makes every concurrent caller await one actual network call instead of racing.
  const refreshInFlight = useRef<Promise<string | null> | null>(null);
  const doRefresh = useCallback(async (): Promise<string | null> => {
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

  const refresh = useCallback((): Promise<string | null> => {
    if (refreshInFlight.current) {
      return refreshInFlight.current;
    }
    // .finally() here, not a try/finally inside doRefresh() - when doRefresh() resolves
    // with no `await` on its path (the common "no stored token yet" case, e.g. the very
    // first silent-refresh attempt on app mount, before login), an inline try/finally runs
    // fully synchronously, clearing refreshInFlight.current to null *before* the
    // `refreshInFlight.current = promise` line below even executes - which then
    // immediately overwrites that null with an already-settled promise, permanently. Every
    // later call would see a truthy ref and short-circuit on it forever, without ever
    // hitting the network again. Chaining .finally() onto the promise instead guarantees
    // the clear always runs as a microtask, strictly after this function returns and the
    // assignment below has already happened - regardless of how fast doRefresh() resolves.
    const promise = doRefresh().finally(() => {
      refreshInFlight.current = null;
    });
    refreshInFlight.current = promise;
    return promise;
  }, [doRefresh]);

  useEffect(() => {
    setRefreshHandler(refresh);
    return () => setRefreshHandler(null);
  }, [refresh]);

  useEffect(() => {
    (async () => {
      const newAccessToken = await refresh();
      if (newAccessToken) {
        try {
          const athlete = await authApi.getMe();
          setUser(athlete);
          setSelfId(athlete.id);
          setIsCoachAccount(athlete.is_coach);
          setIsAdminAccount(athlete.is_admin);
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

  // Fetches the delegated token and the target's profile before swapping anything, so a
  // failure (e.g. the relationship was revoked) leaves the current context untouched.
  const switchToAthlete = useCallback(async (athleteId: string) => {
    const [delegated, profile] = await Promise.all([
      authApi.createDelegatedJwt(athleteId),
      athletesApi.getAthlete(athleteId),
    ]);
    setAccessToken(delegated.token);
    setUser(profile);
    setActiveAthleteId(athleteId);
    // Every mounted query was fetched under the previous context (self, or a different
    // athlete) - nothing in this app's query keys encodes "which athlete," so the only
    // correct way to avoid showing stale/wrong-athlete data is to drop the whole cache.
    queryClient.clear();
  }, [queryClient]);

  const switchToSelf = useCallback(async () => {
    // The stored refresh token was always for the signed-in principal's own OAuth session
    // (delegated JWTs never replace it), so this mints a fresh non-delegated access token
    // regardless of which athlete was active a moment ago.
    const token = await refresh();
    if (!token) {
      return;
    }
    const profile = await authApi.getMe();
    setUser(profile);
    setSelfId(profile.id);
    setActiveAthleteId(null);
    queryClient.clear();
  }, [refresh, queryClient]);

  const value = useMemo(
    () => ({
      user, selfId, activeAthleteId, isCoachAccount, isAdminAccount, isLoading, register, login, logout, setUser,
      switchToAthlete, switchToSelf,
    }),
    [
      user, selfId, activeAthleteId, isCoachAccount, isAdminAccount, isLoading, register, login, logout, setUser,
      switchToAthlete, switchToSelf,
    ],
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
