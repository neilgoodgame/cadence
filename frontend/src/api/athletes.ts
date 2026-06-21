import { apiFetch } from "./client";
import type {
  AthleteUpdate,
  AthleteUpdateResponse,
  BestEffort,
  BestEffortKind,
  BestEffortPeriod,
  DataList,
  FitnessPoint,
  Zone,
  ZoneSet,
  ZoneSetUpdateResponse,
  ZoneType,
} from "./types";

export function updateAthlete(id: string, patch: AthleteUpdate): Promise<AthleteUpdateResponse> {
  return apiFetch<AthleteUpdateResponse>(`/v1/athletes/${id}`, { method: "PATCH", body: patch });
}

export function getFitness(athleteId: string, from?: string, to?: string): Promise<DataList<FitnessPoint>> {
  const params = new URLSearchParams();
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  const query = params.toString();
  return apiFetch<DataList<FitnessPoint>>(`/v1/athletes/${athleteId}/fitness${query ? `?${query}` : ""}`);
}

export function listBestEfforts(
  athleteId: string,
  kind: BestEffortKind,
  period: BestEffortPeriod = "all",
): Promise<{ kind: string; period: string; data: BestEffort[] }> {
  return apiFetch(`/v1/athletes/${athleteId}/best-efforts?kind=${kind}&period=${period}`);
}

export function listZones(athleteId: string): Promise<DataList<ZoneSet>> {
  return apiFetch<DataList<ZoneSet>>(`/v1/athletes/${athleteId}/zones`);
}

export function replaceZoneSet(
  athleteId: string,
  type: ZoneType,
  zones: Zone[],
): Promise<ZoneSetUpdateResponse> {
  return apiFetch<ZoneSetUpdateResponse>(`/v1/athletes/${athleteId}/zones/${type}`, {
    method: "PUT",
    body: { zones },
  });
}
