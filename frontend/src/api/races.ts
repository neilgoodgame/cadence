import { apiFetch } from "./client";
import { toQueryString } from "./activities";
import type {
  DataList,
  Race,
  RaceBlock,
  RaceComment,
  RaceCommentCreate,
  RaceCreate,
  RaceSplits,
  RaceUpdate,
  RaceWeather,
  SplitsMode,
} from "./types";

export function listRaces(): Promise<DataList<Race>> {
  return apiFetch<DataList<Race>>("/v1/races");
}

export function getRace(id: string): Promise<Race> {
  return apiFetch<Race>(`/v1/races/${id}`);
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

export function getRaceBlock(id: string): Promise<RaceBlock> {
  return apiFetch<RaceBlock>(`/v1/races/${id}/block`);
}

export function getRaceSplits(id: string, mode: SplitsMode): Promise<RaceSplits> {
  return apiFetch<RaceSplits>(`/v1/races/${id}/splits${toQueryString({ mode })}`);
}

export function getRaceWeather(id: string): Promise<RaceWeather> {
  return apiFetch<RaceWeather>(`/v1/races/${id}/weather`);
}

export function listRaceComments(id: string): Promise<DataList<RaceComment>> {
  return apiFetch<DataList<RaceComment>>(`/v1/races/${id}/comments`);
}

export function createRaceComment(id: string, data: RaceCommentCreate): Promise<RaceComment> {
  return apiFetch<RaceComment>(`/v1/races/${id}/comments`, { method: "POST", body: data });
}

export function deleteRaceComment(id: string, commentId: string): Promise<void> {
  return apiFetch<void>(`/v1/races/${id}/comments/${commentId}`, { method: "DELETE" });
}
