import { apiFetch } from "./client";
import type { DataList, Share, ShareRole, VirtualCoachCreated } from "./types";

export function listShares(): Promise<DataList<Share>> {
  return apiFetch<DataList<Share>>("/v1/shares");
}

export function createShare(invitee: string, role: ShareRole): Promise<Share> {
  return apiFetch<Share>("/v1/shares", { method: "POST", body: { invitee, role } });
}

export function createVirtualCoach(name: string, scopes: string[]): Promise<VirtualCoachCreated> {
  return apiFetch<VirtualCoachCreated>("/v1/shares/virtual-coach", { method: "POST", body: { name, scopes } });
}

export function updateShare(id: string, role: ShareRole): Promise<Share> {
  return apiFetch<Share>(`/v1/shares/${id}`, { method: "PATCH", body: { role } });
}

export function deleteShare(id: string): Promise<void> {
  return apiFetch<void>(`/v1/shares/${id}`, { method: "DELETE" });
}
