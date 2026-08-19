import { apiFetch, apiFetchForm } from "./client";
import type { Athlete, AuthResponse, Contexts, DelegatedTokenResponse, TokenResponse } from "./types";

export function register(name: string, email: string, password: string): Promise<AuthResponse> {
  return apiFetch<AuthResponse>("/v1/auth/register", {
    method: "POST",
    body: { name, email, password },
    anonymous: true,
  });
}

export function login(email: string, password: string): Promise<AuthResponse> {
  return apiFetch<AuthResponse>("/v1/auth/login", {
    method: "POST",
    body: { email, password },
    anonymous: true,
  });
}

// Public - the link may be opened in a browser with no session of its own (a different
// device, or one where the access token has since expired).
export function verifyEmail(token: string): Promise<void> {
  return apiFetch<void>("/v1/auth/verify-email", {
    method: "POST",
    body: { token },
    anonymous: true,
  });
}

export function resendVerification(): Promise<void> {
  return apiFetch<void>("/v1/auth/resend-verification", { method: "POST" });
}

// The token endpoint authenticates the OAuth client via client_secret_post (see the
// /oauth/token contract and FirstPartyClientConfig in backend_java). A browser bundle
// can't keep a real secret, so this only works because the first-party "secret" is a
// dev-grade shared value, not a credential worth protecting.
const OAUTH_CLIENT_ID = import.meta.env.VITE_OAUTH_CLIENT_ID ?? "cadence-first-party";
const OAUTH_CLIENT_SECRET = import.meta.env.VITE_OAUTH_CLIENT_SECRET ?? "dev-only-secret-change-me";

export function refreshTokens(refreshToken: string): Promise<TokenResponse> {
  return apiFetchForm<TokenResponse>("/oauth/token", {
    grant_type: "refresh_token",
    refresh_token: refreshToken,
    client_id: OAUTH_CLIENT_ID,
    client_secret: OAUTH_CLIENT_SECRET,
  });
}

export function getMe(): Promise<Athlete> {
  return apiFetch<Athlete>("/v1/me");
}

export function getContexts(): Promise<Contexts> {
  return apiFetch<Contexts>("/v1/me/contexts");
}

// Mints a short-lived JWT carrying a different athlete_id than the caller's own - every
// endpoint that resolves "which athlete" implicitly from the token (not from an explicit
// :id in the URL) then transparently acts on athleteId's data instead. The caller (sub)
// doesn't change, so this only succeeds if the caller has an active coach/viewer
// relationship granting access - see backend/authn/views.py::CreateJwtView.
export function createDelegatedJwt(athleteId: string): Promise<DelegatedTokenResponse> {
  return apiFetch<DelegatedTokenResponse>("/v1/auth/jwt", {
    method: "POST",
    body: { athlete_id: athleteId },
  });
}
