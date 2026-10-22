import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getGarminConnection, listGarminSyncs, syncGarmin } from "../../api/garmin";
import type { GarminSyncResult, GarminSyncRun } from "../../api/types";
import { formatDateTime } from "../../lib/format";
import { BatchStatus } from "./BatchStatus";

function isExpired(date: string | null): boolean {
  return !!date && date < new Date().toISOString().slice(0, 10);
}

/** "10 downloaded · 4 already synced · 1 failed" - what a run did. Garmin ids synced before are
 * skipped without downloading ("already synced"); a download can still turn out to be a file
 * already imported another way, which the batch's own counts show. */
function summarise(run: Pick<GarminSyncRun, "requested" | "already_synced" | "failed" | "found">): string {
  if (run.found === 0) return "Nothing new on Garmin";
  const parts = [`${run.requested} downloaded`];
  if (run.already_synced > 0) parts.push(`${run.already_synced} already synced`);
  if (run.failed > 0) parts.push(`${run.failed} failed`);
  return parts.join(" · ");
}

type RunActivity = GarminSyncRun["activities"][number];

/** An upload is still in flight until its batch settles; only then is a missing activity meaningful. */
function isInFlight(run: GarminSyncRun): boolean {
  return (
    (run.batch !== null && run.batch.status !== "completed" && run.batch.status !== "failed") ||
    run.activities.some((a) => a.upload_status === "queued" || a.upload_status === "processing")
  );
}

function activityLabel(a: RunActivity): string {
  switch (a.upload_status) {
    case "queued":
    case "processing":
      return "Importing…";
    case "duplicate":
      return "Already imported";
    case "failed":
      return "Import failed";
    case "skipped":
      return "Skipped — no activity data";
    case null:
      return "Import record cleared";
    default:
      return "Activity no longer in Cadence";
  }
}

function RunRow({ run }: { run: GarminSyncRun }) {
  return (
    <div style={{ padding: "10px 0", borderTop: "1px solid var(--line)", fontSize: 13 }}>
      <div style={{ display: "flex", justifyContent: "space-between", gap: 12 }}>
        <span style={{ fontWeight: 600 }}>{formatDateTime(run.started_at)}</span>
        <span style={{ color: run.error ? "#e0442e" : "var(--ink3)" }}>{run.error ?? summarise(run)}</span>
      </div>
      {run.activities.map((a) => (
        <div key={a.garmin_activity_id} style={{ display: "flex", justifyContent: "space-between", color: "var(--ink2)", marginTop: 4 }}>
          {a.activity_id ? (
            <Link to={`/activities/${a.activity_id}`} style={{ color: "inherit" }}>
              {a.name ?? a.activity_id}
            </Link>
          ) : (
            <span style={{ color: "var(--ink3)" }}>{activityLabel(a)}</span>
          )}
          <span style={{ color: "var(--ink3)" }}>{formatDateTime(a.start_time)}</span>
        </div>
      ))}
    </div>
  );
}

export function GarminSyncCard() {
  const queryClient = useQueryClient();
  const { data: connection } = useQuery({ queryKey: ["garmin-connection"], queryFn: getGarminConnection });
  const { data: history } = useQuery({
    queryKey: ["garmin-syncs"],
    queryFn: () => listGarminSyncs(),
    enabled: connection?.connected === true,
    // The sync returns before its batch has been parsed, so keep refreshing until every run settles.
    refetchInterval: (query) => (query.state.data?.data.some(isInFlight) ? 3000 : false),
  });
  const [result, setResult] = useState<GarminSyncResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const syncMutation = useMutation({
    mutationFn: () => syncGarmin(),
    onSuccess: (r) => {
      setResult(r);
      setError(null);
      queryClient.invalidateQueries({ queryKey: ["garmin-connection"] });
      queryClient.invalidateQueries({ queryKey: ["garmin-syncs"] });
    },
    onError: (err) => {
      setResult(null);
      setError(err instanceof Error ? err.message : "Could not sync with Garmin.");
      queryClient.invalidateQueries({ queryKey: ["garmin-syncs"] });
    },
  });

  if (!connection) return null;
  if (!connection.connected) {
    return (
      <div style={{ fontSize: 13, color: "var(--ink3)" }}>
        Pull activities straight from Garmin Connect — <Link to="/preferences">connect your account in Preferences</Link>.
      </div>
    );
  }

  const expired = isExpired(connection.key_expires_on);
  const lastRun = history?.data[0];
  const canSync = !expired && !syncMutation.isPending;

  return (
    <div style={{ border: "1px solid var(--line)", borderRadius: 14, padding: 20, background: "var(--card)", display: "flex", flexDirection: "column", gap: 12 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12 }}>
        <div>
          <div style={{ fontSize: 15, fontWeight: 700 }}>Sync from Garmin</div>
          <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>
            {connection.last_run_at
              ? `Last synced ${formatDateTime(connection.last_run_at)}${lastRun && !lastRun.error ? ` · ${summarise(lastRun)}` : ""}`
              : "Not synced yet"}
            {connection.last_activity && ` · latest activity ${formatDateTime(connection.last_activity.start_time)}`}
          </div>
        </div>
        <button
          onClick={() => syncMutation.mutate()}
          disabled={!canSync}
          style={{
            padding: "8px 16px",
            borderRadius: 8,
            border: "none",
            background: "var(--ember)",
            color: "#fff",
            fontSize: 13,
            fontWeight: 700,
            whiteSpace: "nowrap",
            opacity: canSync ? 1 : 0.4,
            cursor: canSync ? "pointer" : "not-allowed",
          }}
        >
          {syncMutation.isPending ? "Syncing…" : "Sync now"}
        </button>
      </div>

      {expired && (
        <div style={{ fontSize: 13, color: "#e0442e" }}>
          Your Garmin key expired on {connection.key_expires_on}. <Link to="/preferences">Replace it in Preferences</Link> to sync.
        </div>
      )}
      {error && <div style={{ fontSize: 13, color: "#e0442e" }}>{error}</div>}

      {result && (
        <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <div style={{ fontSize: 13, color: "var(--ink2)" }}>
            {result.found === 0
              ? "Nothing new on Garmin since the last sync."
              : `Found ${result.found} on Garmin — ${summarise(result)}.`}
            {result.truncated && " More activities remain — sync again to continue."}
          </div>
          {result.batch && <BatchStatus key={result.batch.id} initial={{ data: result.batch, retryAfterSeconds: 5 }} />}
        </div>
      )}

      {history && history.data.length > 0 && (
        <details>
          <summary style={{ fontSize: 13, fontWeight: 600, cursor: "pointer" }}>Recent syncs ({history.data.length})</summary>
          <div style={{ marginTop: 6 }}>
            {history.data.map((run) => (
              <RunRow key={run.id} run={run} />
            ))}
          </div>
        </details>
      )}
    </div>
  );
}
