import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { applyThresholdSuggestion, recomputeActivityThresholds, type ThresholdSuggestionField } from "../../api/activities";
import type { Activity } from "../../api/types";

// Detection (and so the "outdated zones" flag) only ever applies to these two sports.
const DETECTABLE_SPORTS = new Set(["bike", "run"]);

interface Row {
  field: ThresholdSuggestionField;
  text: string;
}

function rowsFor(activity: Activity): Row[] {
  const rows: Row[] = [];
  if (activity.suggested_ftp) {
    rows.push({
      field: "ftp",
      text: `We think your FTP may have increased to ${activity.suggested_ftp}W, based on this ride's 20-minute effort.`,
    });
  }
  if (activity.suggested_critical_run_power) {
    rows.push({
      field: "critical_run_power",
      text: `We think your critical running power may have increased to ${activity.suggested_critical_run_power}W, based on this run's 60-minute effort.`,
    });
  }
  if (activity.suggested_threshold_pace) {
    rows.push({
      field: "threshold_pace",
      text: `We think your threshold pace may have improved to ${activity.suggested_threshold_pace}/km, based on this run's 60-minute effort.`,
    });
  }
  return rows;
}

const BUTTON_STYLE: React.CSSProperties = {
  border: "1px solid var(--line)",
  borderRadius: 8,
  padding: "4px 10px",
  fontSize: 12,
  fontWeight: 600,
  background: "transparent",
  color: "var(--ink2)",
};

const ROW_STYLE: React.CSSProperties = {
  fontSize: 13,
  color: "var(--ink2)",
  display: "flex",
  alignItems: "center",
  gap: 10,
  background: "var(--elev)",
  border: "1px solid var(--line)",
  borderRadius: 8,
  padding: "8px 12px",
};

/** Shown above the tabs, in one of three states: (1) this activity's own best effort implies a
 * higher threshold than what's on record (see the backend's ThresholdDetectionService/
 * detect_threshold_increase) - up to two rows for a run (critical_run_power and threshold_pace
 * are detected independently), at most one for a bike (ftp); accepting updates the athlete's
 * profile *and* this activity's own snapshot, dismissing just clears the suggestion, either way
 * it's gone for good (a later, larger effort can still set it again). (2) detection has never run
 * on this activity at all (predates the feature, or was imported, which also skips detection) -
 * a single row offering to check now, via recompute-thresholds. (3) neither - nothing renders.
 * States 1 and 2 can't overlap in practice: a suggestion only ever appears as a side effect of a
 * detection pass, which is exactly what marks the activity checked. */
export function ThresholdSuggestionBanner({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
  const [busyField, setBusyField] = useState<ThresholdSuggestionField | null>(null);
  const [error, setError] = useState<string | null>(null);

  const invalidateActivity = (updated: Activity) => {
    queryClient.setQueryData(["activity", activity.id], updated);
    queryClient.invalidateQueries({ queryKey: ["zones", activity.athlete_id, activity.id] });
  };

  const mutate = useMutation({
    mutationFn: ({ field, accept }: { field: ThresholdSuggestionField; accept: boolean }) =>
      applyThresholdSuggestion(activity.id, field, accept),
    onMutate: ({ field }) => {
      setBusyField(field);
      setError(null);
    },
    onError: (e: Error) => setError(e.message),
    onSuccess: invalidateActivity,
    onSettled: () => setBusyField(null),
  });

  const checkMutation = useMutation({
    mutationFn: () => recomputeActivityThresholds(activity.id),
    onMutate: () => setError(null),
    onError: (e: Error) => setError(e.message),
    onSuccess: invalidateActivity,
  });

  const rows = rowsFor(activity);
  const showStaleFlag = rows.length === 0 && !activity.threshold_checked && DETECTABLE_SPORTS.has(activity.sport);
  if (rows.length === 0 && !showStaleFlag) {
    return null;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {showStaleFlag && (
        <div style={ROW_STYLE}>
          <span style={{ flex: 1 }}>
            This activity predates automatic threshold detection, so its zones may be based on outdated values.
          </span>
          <button
            onClick={() => checkMutation.mutate()}
            disabled={checkMutation.isPending}
            style={{ ...BUTTON_STYLE, background: "var(--ember)", color: "#fff", border: "none", cursor: checkMutation.isPending ? "wait" : "pointer" }}
          >
            {checkMutation.isPending ? "Checking…" : "Check for updates"}
          </button>
        </div>
      )}
      {rows.map((row) => (
        <div key={row.field} style={ROW_STYLE}>
          <span style={{ flex: 1 }}>{row.text}</span>
          <button
            onClick={() => mutate.mutate({ field: row.field, accept: true })}
            disabled={busyField !== null}
            style={{ ...BUTTON_STYLE, background: "var(--ember)", color: "#fff", border: "none", cursor: busyField ? "wait" : "pointer" }}
          >
            Update my profile
          </button>
          <button
            onClick={() => mutate.mutate({ field: row.field, accept: false })}
            disabled={busyField !== null}
            style={{ ...BUTTON_STYLE, cursor: busyField ? "wait" : "pointer" }}
          >
            Dismiss
          </button>
        </div>
      ))}
      {error && <div style={{ fontSize: 12, color: "#e0442e" }}>{error}</div>}
    </div>
  );
}
