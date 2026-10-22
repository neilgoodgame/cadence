import { apiFetch } from "./client";
import type { DataList, GarminConnection, GarminConnectionUpdate, GarminSyncResult, GarminSyncRun } from "./types";

export function getGarminConnection(): Promise<GarminConnection> {
  return apiFetch<GarminConnection>("/v1/garmin/connection");
}

export function saveGarminConnection(update: GarminConnectionUpdate): Promise<GarminConnection> {
  return apiFetch<GarminConnection>("/v1/garmin/connection", { method: "PUT", body: update });
}

export function deleteGarminConnection(): Promise<void> {
  return apiFetch<void>("/v1/garmin/connection", { method: "DELETE" });
}

export function syncGarmin(params: { limit?: number; since?: string } = {}): Promise<GarminSyncResult> {
  return apiFetch<GarminSyncResult>("/v1/garmin/sync", { method: "POST", body: params });
}

export function listGarminSyncs(limit = 20): Promise<DataList<GarminSyncRun>> {
  return apiFetch<DataList<GarminSyncRun>>(`/v1/garmin/syncs?limit=${limit}`);
}
