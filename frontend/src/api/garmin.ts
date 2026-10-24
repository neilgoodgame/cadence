import { apiFetch } from "./client";
import type {
  DataList,
  GarminConnection,
  GarminConnectionUpdate,
  GarminSyncParams,
  GarminWorkoutLinks,
  GarminWorkoutOp,
  GarminWorkoutOpInput,
  GarminSyncResult,
  GarminSyncRun,
} from "./types";

export function getGarminConnection(): Promise<GarminConnection> {
  return apiFetch<GarminConnection>("/v1/garmin/connection");
}

export function saveGarminConnection(update: GarminConnectionUpdate): Promise<GarminConnection> {
  return apiFetch<GarminConnection>("/v1/garmin/connection", { method: "PUT", body: update });
}

export function deleteGarminConnection(): Promise<void> {
  return apiFetch<void>("/v1/garmin/connection", { method: "DELETE" });
}

export function syncGarmin(params: GarminSyncParams = {}): Promise<GarminSyncResult> {
  return apiFetch<GarminSyncResult>("/v1/garmin/sync", { method: "POST", body: params });
}

export function listGarminSyncs(limit = 20): Promise<DataList<GarminSyncRun>> {
  return apiFetch<DataList<GarminSyncRun>>(`/v1/garmin/syncs?limit=${limit}`);
}

/** Queues something for the Garmin bridge to do; null (204) when there was nothing to do. */
export function queueGarminWorkoutOp(op: GarminWorkoutOpInput): Promise<GarminWorkoutOp | null> {
  return apiFetch<GarminWorkoutOp | undefined>("/v1/garmin/workout-ops", { method: "POST", body: op }).then((r) => r ?? null);
}

export function listGarminWorkoutOps(status: "pending" | "failed" | "all" = "all"): Promise<DataList<GarminWorkoutOp>> {
  return apiFetch<DataList<GarminWorkoutOp>>(`/v1/garmin/workout-ops?status=${status}`);
}

export function discardGarminWorkoutOp(id: string): Promise<void> {
  return apiFetch<void>(`/v1/garmin/workout-ops/${id}`, { method: "DELETE" });
}

export function getGarminWorkoutLinks(): Promise<GarminWorkoutLinks> {
  return apiFetch<GarminWorkoutLinks>("/v1/garmin/workout-links");
}
