import { apiFetch, apiFetchStream } from "./client";
import type {
  Athlete,
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

// Permission-checked via user_may_read(sub, id) - works for any caller with an active
// coach/viewer relationship to this athlete, using the caller's own (non-delegated) token.
export function getAthlete(id: string): Promise<Athlete> {
  return apiFetch<Athlete>(`/v1/athletes/${id}`);
}

export function updateAthlete(id: string, patch: AthleteUpdate): Promise<AthleteUpdateResponse> {
  return apiFetch<AthleteUpdateResponse>(`/v1/athletes/${id}`, { method: "PATCH", body: patch });
}

export function recomputeTss(athleteId: string): Promise<{ updated: number }> {
  return apiFetch<{ updated: number }>(`/v1/athletes/${athleteId}/recompute-tss`, { method: "POST" });
}

export function getFitness(athleteId: string, from?: string, to?: string): Promise<DataList<FitnessPoint>> {
  const params = new URLSearchParams();
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  const query = params.toString();
  return apiFetch<DataList<FitnessPoint>>(`/v1/athletes/${athleteId}/fitness${query ? `?${query}` : ""}`);
}

export function excludeActivityFromBestEfforts(athleteId: string, activityId: string, kind: BestEffortKind): Promise<void> {
  return apiFetch(`/v1/athletes/${athleteId}/best-efforts/by-activity/${activityId}?kind=${kind}`, { method: "DELETE" });
}

async function* sseBlocks(response: Response): AsyncGenerator<{ event: string; data: string }> {
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const parseBlock = function* (block: string): Generator<{ event: string; data: string }> {
    let eventName = "message";
    let data = "";
    for (const line of block.split("\n")) {
      if (line.startsWith("event:")) eventName = line.slice(6).trim();
      else if (line.startsWith("data:")) data = line.slice(5).trim();
    }
    if (data) yield { event: eventName, data };
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const blocks = buffer.split("\n\n");
    buffer = blocks.pop()!;
    for (const block of blocks) {
      if (block.trim()) yield* parseBlock(block);
    }
  }
  if (buffer.trim()) yield* parseBlock(buffer);
}

export type RecomputeEvent =
  | { type: "progress"; current: number; total: number }
  | { type: "done"; processed: number };

export async function* recomputeBestEffortsStream(
  athleteId: string,
  kind?: BestEffortKind,
): AsyncGenerator<RecomputeEvent> {
  const qs = kind ? `?kind=${kind}` : "";
  const response = await apiFetchStream(`/v1/athletes/${athleteId}/best-efforts/recompute${qs}`, { method: "POST" });
  for await (const { event, data } of sseBlocks(response)) {
    try {
      const parsed = JSON.parse(data);
      if (event === "done") yield { type: "done", processed: parsed.processed ?? 0 };
      else yield { type: "progress", current: parsed.current ?? 0, total: parsed.total ?? 0 };
    } catch { /* ignore malformed */ }
  }
}

export type RecomputeStatsEvent =
  | { type: "progress"; current: number; total: number }
  | { type: "done"; updated: number };

export async function* recomputeStatsStream(athleteId: string): AsyncGenerator<RecomputeStatsEvent> {
  const response = await apiFetchStream(`/v1/athletes/${athleteId}/recompute-stats`, { method: "POST" });
  for await (const { event, data } of sseBlocks(response)) {
    try {
      const parsed = JSON.parse(data);
      if (event === "done") yield { type: "done", updated: parsed.updated ?? 0 };
      else yield { type: "progress", current: parsed.current ?? 0, total: parsed.total ?? 0 };
    } catch { /* ignore malformed */ }
  }
}

export function trimBestEfforts(athleteId: string): Promise<void> {
  return apiFetch(`/v1/athletes/${athleteId}/best-efforts/trim`, { method: "POST" });
}

export function listBestEfforts(
  athleteId: string,
  kind: BestEffortKind,
  period: BestEffortPeriod = "all",
): Promise<{ kind: string; period: string; data: BestEffort[] }> {
  return apiFetch(`/v1/athletes/${athleteId}/best-efforts?kind=${kind}&period=${period}`);
}

/** With `activityId`, bike_power/run_power/pace's reference comes from that activity's own
 * threshold snapshot instead of the athlete's current profile - see ZonesTab.tsx. */
export function listZones(athleteId: string, activityId?: string): Promise<DataList<ZoneSet>> {
  const query = activityId ? `?activity_id=${activityId}` : "";
  return apiFetch<DataList<ZoneSet>>(`/v1/athletes/${athleteId}/zones${query}`);
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
