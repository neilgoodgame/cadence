import { apiFetch, apiFetchStream } from "./client";
import { toQueryString } from "./activities";
import type {
  Athlete,
  AthleteUpdate,
  AthleteUpdateResponse,
  BestEffort,
  BestEffortKind,
  BestEffortPeriod,
  DataList,
  FitnessPoint,
  ThresholdFieldName,
  ThresholdHistoryResponse,
  ThresholdsSummary,
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

// Comfortably above the slowest single-activity latency actually observed in production
// (~11s under load - see infra/README.md's Step 16) while still catching a genuine stall in
// under a minute rather than however long a browser tab happens to stay open. A long-running
// recompute's SSE connection can go quiet forever without the browser ever seeing a clean
// network error - e.g. a backend deploy restarting the server process mid-stream doesn't
// reliably propagate a TCP close through CloudFront, so `reader.read()` just never resolves.
// Without this, `for await` on the raw stream suspends indefinitely: the UI keeps showing
// whatever progress it last received (and, for sections with an elapsed-time counter, a timer
// that keeps ticking regardless, since it's driven by the browser's own clock, not the
// connection) - a dead connection dressed up as a slow one.
const STALL_TIMEOUT_MS = 60_000;

// Exported only for withStallTimeout.test.ts - every real call site above supplies
// STALL_TIMEOUT_MS itself.
export async function* withStallTimeout<T>(source: AsyncGenerator<T>, timeoutMs: number): AsyncGenerator<T> {
  while (true) {
    let timer: ReturnType<typeof setTimeout>;
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(
        () => reject(new Error(`No update in over ${Math.round(timeoutMs / 1000)}s - connection lost.`)),
        timeoutMs,
      );
    });
    try {
      const result = await Promise.race([source.next(), timeout]);
      if (result.done) return;
      yield result.value;
    }
    finally {
      clearTimeout(timer!);
    }
  }
}

export type RecomputeBestEffortsEvent =
  | { type: "progress"; current: number; total: number }
  | { type: "done"; processed: number };

// SSE, same as recomputeStatsStream/recomputeThresholdHistoryStream below - the endpoint
// (BestEffortController#recompute) only ever streamed progress, never returned a pollable
// job. An earlier version of this client assumed a Celery-style job+polling contract that
// this backend never actually implemented (no GET .../recompute/{jobId} exists) - every call
// silently hung waiting for the whole stream to buffer, then failed parsing it as JSON.
export async function* recomputeBestEffortsStream(
  athleteId: string,
  kind?: BestEffortKind,
): AsyncGenerator<RecomputeBestEffortsEvent> {
  const response = await apiFetchStream(
    `/v1/athletes/${athleteId}/best-efforts/recompute${toQueryString({ kind })}`,
    { method: "POST" },
  );
  for await (const { event, data } of withStallTimeout(sseBlocks(response), STALL_TIMEOUT_MS)) {
    // Unrelated to real progress - just proves the connection is still alive between
    // progress events, since one item's real processing time has no upper bound (see
    // SseHeartbeat.java's Javadoc for the incident this fixes).
    if (event === "heartbeat") continue;
    try {
      const parsed = JSON.parse(data);
      if (event === "done") yield { type: "done", processed: parsed.processed ?? 0 };
      else yield { type: "progress", current: parsed.current ?? 0, total: parsed.total ?? 0 };
    } catch { /* ignore malformed */ }
  }
}

export type RecomputeCurvesEvent =
  | { type: "progress"; current: number; total: number }
  | { type: "done"; processed: number };

// Backfills duration curves for activities that never got them from the upload pipeline -
// restoring from an export being the main case (ImportReader never computes them). SSE, same
// shape as recomputeBestEffortsStream above.
export async function* recomputeCurvesStream(athleteId: string): AsyncGenerator<RecomputeCurvesEvent> {
  const response = await apiFetchStream(`/v1/athletes/${athleteId}/curves/recompute`, { method: "POST" });
  for await (const { event, data } of withStallTimeout(sseBlocks(response), STALL_TIMEOUT_MS)) {
    // Unrelated to real progress - just proves the connection is still alive between
    // progress events, since one item's real processing time has no upper bound (see
    // SseHeartbeat.java's Javadoc for the incident this fixes).
    if (event === "heartbeat") continue;
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
  for await (const { event, data } of withStallTimeout(sseBlocks(response), STALL_TIMEOUT_MS)) {
    // Unrelated to real progress - just proves the connection is still alive between
    // progress events, since one item's real processing time has no upper bound (see
    // SseHeartbeat.java's Javadoc for the incident this fixes).
    if (event === "heartbeat") continue;
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

/** Current + previous value and staleness for all three fields at once - the dashboard widget's
 * summary read. No recompute - stale just means "will update on the next activity, or refresh
 * below." */
export function getThresholds(athleteId: string): Promise<ThresholdsSummary> {
  return apiFetch<ThresholdsSummary>(`/v1/athletes/${athleteId}/thresholds`);
}

/** The full ledger for one field, most recent first - backs the history screen the dashboard
 * widget's per-field links lead to. */
export function getThresholdHistory(athleteId: string, field: ThresholdFieldName): Promise<ThresholdHistoryResponse> {
  return apiFetch<ThresholdHistoryResponse>(`/v1/athletes/${athleteId}/threshold-history${toQueryString({ field })}`);
}

/** The dashboard's "this will update on your next activity, or refresh now" manual action -
 * synchronous, cheap (a single current-window scan for one field, same as the ingest hook).
 * Returns the same shape as getThresholds, for all three fields. */
export function refreshThreshold(athleteId: string, field: ThresholdFieldName): Promise<ThresholdsSummary> {
  return apiFetch<ThresholdsSummary>(`/v1/athletes/${athleteId}/thresholds/refresh${toQueryString({ field })}`, {
    method: "POST",
  });
}

export type RecomputeThresholdHistoryEvent =
  | { type: "progress"; current: number; total: number }
  | { type: "done"; total: number };

/** Rebuilds the entire history ledger for one field from scratch, replaying the athlete's
 * activities oldest-first - for bootstrapping history on an existing account, or after changing
 * the window/sanity-% settings below. */
export async function* recomputeThresholdHistoryStream(
  athleteId: string,
  field: ThresholdFieldName,
): AsyncGenerator<RecomputeThresholdHistoryEvent> {
  const response = await apiFetchStream(
    `/v1/athletes/${athleteId}/recompute-threshold-history${toQueryString({ field })}`,
    { method: "POST" },
  );
  for await (const { event, data } of withStallTimeout(sseBlocks(response), STALL_TIMEOUT_MS)) {
    // Unrelated to real progress - just proves the connection is still alive between
    // progress events, since one item's real processing time has no upper bound (see
    // SseHeartbeat.java's Javadoc for the incident this fixes).
    if (event === "heartbeat") continue;
    try {
      const parsed = JSON.parse(data);
      if (event === "done") yield { type: "done", total: parsed.total ?? 0 };
      else yield { type: "progress", current: parsed.current ?? 0, total: parsed.total ?? 0 };
    } catch { /* ignore malformed */ }
  }
}

export function listBestEfforts(
  athleteId: string,
  kind: BestEffortKind,
  period: BestEffortPeriod = "all",
): Promise<{ kind: string; period: string; data: BestEffort[] }> {
  return apiFetch(`/v1/athletes/${athleteId}/best-efforts?kind=${kind}&period=${period}`);
}

/** With `activityId`, bike_power/run_power/pace's reference comes from the ThresholdHistory
 * ledger entry effective as of that activity's own date, instead of the athlete's current
 * profile - see ZonesTab.tsx. */
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
