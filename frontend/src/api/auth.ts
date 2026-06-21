import { apiFetch, apiFetchForm } from "./client";
import type { Athlete, AuthResponse, Contexts, TokenResponse } from "./types";

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

export function refreshTokens(refreshToken: string): Promise<TokenResponse> {
  return apiFetchForm<TokenResponse>("/oauth/token", {
    grant_type: "refresh_token",
    refresh_token: refreshToken,
  });
}

export function getMe(): Promise<Athlete> {
  return apiFetch<Athlete>("/v1/me");
}

export function getContexts(): Promise<Contexts> {
  return apiFetch<Contexts>("/v1/me/contexts");
}
