import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { applyThresholdSuggestion, type ThresholdSuggestionField } from "../../api/activities";
import type { Activity } from "../../api/types";

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

/** Shown above the tabs when this activity's own best effort implies a higher threshold than
 * what's on record (see the backend's ThresholdDetectionService/detect_threshold_increase) - up
 * to two rows for a run (critical_run_power and threshold_pace are detected independently), at
 * most one for a bike (ftp). Accepting updates the athlete's profile *and* this activity's own
 * snapshot; dismissing just clears the suggestion - either way it's gone for good (a later,
 * larger effort can still set it again). */
export function ThresholdSuggestionBanner({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
  const [busyField, setBusyField] = useState<ThresholdSuggestionField | null>(null);
  const [error, setError] = useState<string | null>(null);

  const mutate = useMutation({
    mutationFn: ({ field, accept }: { field: ThresholdSuggestionField; accept: boolean }) =>
      applyThresholdSuggestion(activity.id, field, accept),
    onMutate: ({ field }) => {
      setBusyField(field);
      setError(null);
    },
    onError: (e: Error) => setError(e.message),
    onSettled: () => {
      setBusyField(null);
      queryClient.invalidateQueries({ queryKey: ["activity", activity.id] });
      queryClient.invalidateQueries({ queryKey: ["zones", activity.athlete_id, activity.id] });
    },
  });

  const rows = rowsFor(activity);
  if (rows.length === 0) {
    return null;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {rows.map((row) => (
        <div
          key={row.field}
          style={{
            fontSize: 13,
            color: "var(--ink2)",
            display: "flex",
            alignItems: "center",
            gap: 10,
            background: "var(--elev)",
            border: "1px solid var(--line)",
            borderRadius: 8,
            padding: "8px 12px",
          }}
        >
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
