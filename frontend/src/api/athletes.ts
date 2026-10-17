import { apiFetch, apiFetchStream, apiFetchWithHeaders } from "./client";
import { toQueryString } from "./activities";
import type {
  Athlete,
  AthleteUpdate,
  AthleteUpdateResponse,
  BestEffort,
  BestEffortKind,
  BestEffortPeriod,
  BestEffortRecomputeJob,
  DataList,
  DurabilityResponse,
  FitnessPoint,
  RecentTopEffort,
  ThresholdFieldName,
  ThresholdHistoryPoint,
  ThresholdHistoryResponse,
  ThresholdSuggestion,
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

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// Django's BestEffortRecomputeView dispatches a Celery job + polls it (job.status
// queued/processing/ready/failed) instead of streaming SSE like Java's BestEffortController -
// a deliberate divergence (see its own code comment) to avoid holding a gunicorn sync worker
// open for as long as a full-account recompute can take. See issue #436. Polls on the
// response's own Retry-After header, same cadence as ExportTab.tsx's usePolling.
async function* pollBestEffortsJob(
  athleteId: string,
  initial: BestEffortRecomputeJob,
  initialRetryAfterSeconds: number,
): AsyncGenerator<RecomputeBestEffortsEvent> {
  let job = initial;
  let retryAfterSeconds = initialRetryAfterSeconds;
  while (job.status === "queued" || job.status === "processing") {
    yield { type: "progress", current: job.processed_items, total: job.total_items ?? 0 };
    await sleep(retryAfterSeconds * 1000);
    const next = await apiFetchWithHeaders<BestEffortRecomputeJob>(
      `/v1/athletes/${athleteId}/best-efforts/recompute/${job.id}`,
    );
    job = next.data;
    retryAfterSeconds = next.retryAfterSeconds ?? 5;
  }
  if (job.status === "failed") throw new Error(job.error_message || "Recompute failed.");
  yield { type: "done", processed: job.processed_items };
}

// SSE for Java (BestEffortController#recompute) - Django instead returns a pollable job (see
// pollBestEffortsJob above). An earlier version of this client assumed every backend used one
// contract or the other and hung against whichever one it didn't expect - detect the actual
// shape from the initial response instead of assuming.
export async function* recomputeBestEffortsStream(
  athleteId: string,
  kind?: BestEffortKind,
): AsyncGenerator<RecomputeBestEffortsEvent> {
  const response = await apiFetchStream(
    `/v1/athletes/${athleteId}/best-efforts/recompute${toQueryString({ kind })}`,
    { method: "POST" },
  );

  if (!(response.headers.get("Content-Type") ?? "").includes("text/event-stream")) {
    const job = (await response.json()) as BestEffortRecomputeJob;
    const retryAfterHeader = response.headers.get("Retry-After");
    yield* pollBestEffortsJob(athleteId, job, retryAfterHeader ? Number(retryAfterHeader) : 5);
    return;
  }

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

/** Aerobic decoupling sessions (trend + rolling average) and durability (best power once
 * tired) - backs the Best Efforts screen's Durability view and the Activity Analysis "Aerobic
 * decoupling" card's durability strip link. */
export function getDurability(
  athleteId: string,
  sport: "all" | "ride" | "run" = "all",
  period: "4w" | "16w" | "1y" | "all" = "16w",
): Promise<DurabilityResponse> {
  return apiFetch(`/v1/athletes/${athleteId}/durability?sport=${sport}&period=${period}`);
}

/** Backs the Dashboard's "Top efforts this week" card - which of the given activities ranked
 * top-3 in any of the athlete's best-effort leaderboards. Takes explicit activity ids rather
 * than a "how far back" window - deciding what counts as recent is a local-date/timezone
 * concern the caller already has to get right for its own display (the This Week calendar),
 * so it's on the caller to pass in exactly the activities it cares about. See RecentTopEffort's
 * own doc comment for the exact response shape. Skips the request entirely (never calls
 * `apiFetch`) when `activityIds` is empty - there's nothing to ask about, and the backend
 * would just return no data anyway. */
export function getActivityBestEffortRanks(athleteId: string, activityIds: string[]): Promise<{ data: RecentTopEffort[] }> {
  if (activityIds.length === 0) {
    return Promise.resolve({ data: [] });
  }
  return apiFetch(`/v1/athletes/${athleteId}/best-efforts/ranks${toQueryString({ activity_ids: activityIds.join(",") })}`);
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

/** Cached (24h server-side) list of actionable Threshold suggestions - backs the Dashboard
 * banner and the Thresholds & zones "Suggestions" card. Additive UI: callers should render
 * nothing on loading/error, not a spinner or error state. */
export function listThresholdSuggestions(athleteId: string): Promise<DataList<ThresholdSuggestion>> {
  return apiFetch<DataList<ThresholdSuggestion>>(`/v1/athletes/${athleteId}/threshold-suggestions`);
}

/** Accepts a "rejected" candidate - records it as a real ledger entry (bypassing the sanity
 * band for this activity from now on) and updates the live profile value. Returns the new
 * ledger entry. */
export function acceptThresholdSuggestion(athleteId: string, suggestionId: string): Promise<ThresholdHistoryPoint> {
  return apiFetch<ThresholdHistoryPoint>(`/v1/athletes/${athleteId}/threshold-suggestions/${suggestionId}/accept`, {
    method: "POST",
  });
}

/** Undoes accept(): removes the override and reverts to the normal windowed recompute. */
export function undoAcceptThresholdSuggestion(athleteId: string, suggestionId: string): Promise<void> {
  return apiFetch(`/v1/athletes/${athleteId}/threshold-suggestions/${suggestionId}/accept`, { method: "DELETE" });
}

export function dismissThresholdSuggestion(athleteId: string, suggestionId: string): Promise<void> {
  return apiFetch(`/v1/athletes/${athleteId}/threshold-suggestions/${suggestionId}/dismiss`, { method: "POST" });
}

export function undoDismissThresholdSuggestion(athleteId: string, suggestionId: string): Promise<void> {
  return apiFetch(`/v1/athletes/${athleteId}/threshold-suggestions/${suggestionId}/dismiss`, { method: "DELETE" });
}
