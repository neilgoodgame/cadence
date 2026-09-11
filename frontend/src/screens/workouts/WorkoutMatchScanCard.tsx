import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createWorkoutMatchScan, getWorkoutMatchScan } from "../../api/matchScans";
import { updateActivity } from "../../api/activities";
import type { WorkoutMatchScan, WorkoutMatchScanCandidate } from "../../api/types";
import { Card } from "../../components/Card";
import { formatDate, formatDuration } from "../../lib/format";
import { usePolling } from "../../lib/usePolling";

const TERMINAL_STATUSES = new Set(["ready", "failed"]);

const GRID_COLS = "minmax(140px,1.3fr) 0.6fr 0.7fr 0.55fr 0.9fr";

const actionBtn = {
  border: "1px solid var(--line)",
  borderRadius: 8,
  padding: "5px 12px",
  fontSize: 12,
  fontWeight: 600,
  background: "transparent",
  color: "var(--ink2)",
};

function ColHeaders() {
  const style = { fontSize: 10, fontWeight: 700, letterSpacing: "0.06em", color: "var(--ink3)", textTransform: "uppercase" as const };
  return (
    <div style={{ display: "grid", gridTemplateColumns: GRID_COLS, gap: 10, padding: "0 4px 8px" }}>
      <span style={style}>Activity</span>
      <span style={style}>Match</span>
      <span style={style}>Duration diff</span>
      <span style={style}>Coverage</span>
      <span />
    </div>
  );
}

/** A workout template is reusable, so more than one candidate can legitimately be applied to
 * the same workout - each row applies independently rather than the list being a single pick. */
function CandidateRow({
  workoutId,
  candidate,
  onApplied,
}: {
  workoutId: string;
  candidate: WorkoutMatchScanCandidate;
  onApplied: () => void;
}) {
  const [applied, setApplied] = useState(false);

  const applyMutation = useMutation({
    mutationFn: () => updateActivity(candidate.activity_id, { workout_id: workoutId }),
    onSuccess: () => {
      setApplied(true);
      onApplied();
    },
  });

  return (
    <div style={{ display: "grid", gridTemplateColumns: GRID_COLS, gap: 10, alignItems: "center", padding: "8px 4px", borderTop: "1px solid var(--line)" }}>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)" }}>{candidate.name}</div>
        <div className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>
          {formatDate(candidate.date)} · {formatDuration(candidate.moving_time)}
          {candidate.implied_ftp != null ? ` · implied FTP ${candidate.implied_ftp}W` : ""}
        </div>
      </div>
      <span className="mono" style={{ fontSize: 14, fontWeight: 700, color: "var(--ink)" }}>
        {Math.round(candidate.correlation * 100)}%
      </span>
      <span className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>
        {formatDuration(candidate.duration_diff_seconds)}
      </span>
      <span className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>
        {Math.round(candidate.coverage * 100)}%
      </span>
      <div style={{ textAlign: "right" }}>
        {applyMutation.isError && (
          <div style={{ fontSize: 11, color: "#e0442e", marginBottom: 4 }}>{(applyMutation.error as Error).message}</div>
        )}
        <button
          onClick={() => applyMutation.mutate()}
          disabled={applied || applyMutation.isPending}
          style={{
            ...actionBtn,
            background: applied ? "rgba(47,166,106,0.13)" : "transparent",
            color: applied ? "#2fa66a" : "var(--ink2)",
            cursor: applied || applyMutation.isPending ? "default" : "pointer",
          }}
        >
          {applied ? "Applied" : applyMutation.isPending ? "Applying…" : "Apply match"}
        </button>
      </div>
    </div>
  );
}

function ScanResults({
  initial,
  workoutId,
  onApplied,
}: {
  initial: { data: WorkoutMatchScan; retryAfterSeconds: number | null };
  workoutId: string;
  onApplied: () => void;
}) {
  const scan = usePolling(initial, (id) => getWorkoutMatchScan(workoutId, id), (s) => s.id, (s) => TERMINAL_STATUSES.has(s.status));

  if (scan.status === "failed") {
    return <div style={{ fontSize: 13, color: "#e0442e" }}>{scan.error_message ?? "Scan failed."}</div>;
  }

  if (scan.status === "queued" || scan.status === "processing") {
    const progress = scan.total_candidates != null ? ` (${scan.processed_candidates}/${scan.total_candidates})` : "";
    return <div style={{ fontSize: 13, color: "var(--ink2)" }}>{scan.status === "queued" ? "Queued…" : `Scanning…${progress}`}</div>;
  }

  if (scan.candidates.length === 0) {
    return <div style={{ fontSize: 13, color: "var(--ink3)" }}>No likely matches found among your unlinked activities.</div>;
  }

  return (
    <div>
      <ColHeaders />
      {scan.candidates.map((candidate) => (
        <CandidateRow key={candidate.activity_id} workoutId={workoutId} candidate={candidate} onApplied={onApplied} />
      ))}
    </div>
  );
}

/** Triggers POST /v1/workouts/{id}/match-scans (a Pearson-correlation scan of the athlete's own
 * unmatched, same-sport activities), polls it to completion, and lets the athlete apply one or
 * more ranked candidates. On-demand, not auto-run on mount - mirrors DuplicatesCard.tsx's
 * trigger pattern. */
export function WorkoutMatchScanCard({ workoutId }: { workoutId: string }) {
  const queryClient = useQueryClient();
  const [scanResult, setScanResult] = useState<{ data: WorkoutMatchScan; retryAfterSeconds: number | null } | null>(null);

  const triggerMutation = useMutation({
    mutationFn: () => createWorkoutMatchScan(workoutId),
    onSuccess: setScanResult,
  });

  return (
    <Card>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: scanResult ? 12 : 0 }}>
        <div className="mono" style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", color: "var(--ink3)" }}>
          CANDIDATE MATCHES
        </div>
        {!scanResult && (
          <button onClick={() => triggerMutation.mutate()} disabled={triggerMutation.isPending} style={{ ...actionBtn, cursor: triggerMutation.isPending ? "wait" : "pointer" }}>
            {triggerMutation.isPending ? "Starting…" : "Scan for matches"}
          </button>
        )}
      </div>
      {triggerMutation.isError && <div style={{ fontSize: 13, color: "#e0442e" }}>{(triggerMutation.error as Error).message}</div>}
      {scanResult && (
        <ScanResults
          initial={scanResult}
          workoutId={workoutId}
          onApplied={() => queryClient.invalidateQueries({ queryKey: ["workout-matches", workoutId] })}
        />
      )}
    </Card>
  );
}
