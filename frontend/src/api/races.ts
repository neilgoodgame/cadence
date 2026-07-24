import { apiFetch } from "./client";
import type { DataList, Race, RaceCreate, RaceUpdate } from "./types";

export function listRaces(): Promise<DataList<Race>> {
  return apiFetch<DataList<Race>>("/v1/races");
}

export function createRace(data: RaceCreate): Promise<Race> {
  return apiFetch<Race>("/v1/races", { method: "POST", body: data });
}

export function updateRace(id: string, data: RaceUpdate): Promise<Race> {
  return apiFetch<Race>(`/v1/races/${id}`, { method: "PATCH", body: data });
}

export function deleteRace(id: string): Promise<void> {
  return apiFetch<void>(`/v1/races/${id}`, { method: "DELETE" });
}
