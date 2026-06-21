import { apiFetch } from "./client";
import type { AccessToken, AccessTokenWithSecret, DataList } from "./types";

export function listAccessTokens(): Promise<DataList<AccessToken>> {
  return apiFetch<DataList<AccessToken>>("/v1/auth/tokens");
}

export function createAccessToken(
  name: string,
  scopes: string[],
  expiresAt: string | null,
): Promise<AccessTokenWithSecret> {
  return apiFetch<AccessTokenWithSecret>("/v1/auth/tokens", {
    method: "POST",
    body: { name, scopes, expires_at: expiresAt },
  });
}

export function rotateAccessToken(id: string): Promise<AccessTokenWithSecret> {
  return apiFetch<AccessTokenWithSecret>(`/v1/auth/tokens/${id}/rotate`, { method: "POST" });
}

export function revokeAccessToken(id: string): Promise<void> {
  return apiFetch<void>(`/v1/auth/tokens/${id}`, { method: "DELETE" });
}
