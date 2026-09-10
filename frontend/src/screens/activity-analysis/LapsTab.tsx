import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { getLaps, inferWorkout, regenerateActivityLaps } from "../../api/activities";
import { ApiError, type Lap, type Sport } from "../../api/types";
import { formatDuration } from "../../lib/format";
import { kindLabel } from "../workouts/workoutTree";

/** Lightweight, reference-free target string for a lap's step (no FTP/max-HR/threshold-pace
 * available here to convert a % target into an absolute value - see workoutTree's targetInfo
 * for that fuller version, used in the workout builder where those references are on hand). */
function formatStepTarget(lap: Lap): string | null {
  if (!lap.step_target_type) return null;
  const lo = lap.step_target_low ?? 0;
  const hi = lap.step_target_high ?? lo;
  const range = (unit: string) => (lo === hi ? `${lo}${unit}` : `${lo}–${hi}${unit}`);
  switch (lap.step_target_type) {
    case "power":
      return lap.step_power_unit === "watts" ? range("W") : range("% FTP");
    case "hr":
      return range("% max HR");
    case "pace":
      return range("% threshold pace");
    case "cadence":
      return range(" rpm");
    default:
      return "Open";
  }
}

function RegenerateLapsButton({ activityId }: { activityId: string }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () => regenerateActivityLaps(activityId),
    onSuccess: (updated) => queryClient.setQueryData(["activity-laps", activityId], updated),
  });
  const error = mutation.isError
    ? mutation.error instanceof ApiError
      ? mutation.error.message
      : "Couldn't regenerate laps. Try again."
    : null;
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <button
        type="button"
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
        title="Replace this activity's laps with ones derived from its matched workout's steps."
        style={{
          fontSize: 12,
          fontWeight: 600,
          padding: "5px 10px",
          borderRadius: 8,
          border: "1px solid var(--line)",
          background: "none",
          color: mutation.isSuccess ? "var(--ember)" : "var(--ink3)",
          cursor: "pointer",
          opacity: mutation.isPending ? 0.6 : 1,
        }}
      >
        {mutation.isPending ? "Regenerating…" : "↺ Regenerate from workout"}
      </button>
      {error && <span style={{ fontSize: 12.5, color: "#e0442e" }}>{error}</span>}
    </div>
  );
}

export function LapsTab({ activityId, sport, workoutId }: { activityId: string; sport: Sport; workoutId: string | null }) {
  const navigate = useNavigate();
  const { data } = useQuery({ queryKey: ["activity-laps", activityId], queryFn: () => getLaps(activityId) });
  const laps = data?.data ?? [];
  const maxPower = Math.max(1, ...laps.map((l) => l.avg_power ?? 0));
  const [autoDetectRepeats, setAutoDetectRepeats] = useState(true);

  const inferMutation = useMutation({
    mutationFn: () => inferWorkout(activityId, autoDetectRepeats),
    onSuccess: (draft) => navigate("/workouts", { state: { inferredDraft: draft } }),
  });
  const inferError = inferMutation.isError
    ? inferMutation.error instanceof ApiError
      ? inferMutation.error.message
      : "Couldn't infer a workout from these laps. Try again."
    : null;

  if (laps.length === 0) {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {workoutId && <RegenerateLapsButton activityId={activityId} />}
        <div style={{ color: "var(--ink3)", fontSize: 13 }}>No laps recorded.</div>
      </div>
    );
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
        {(sport === "bike" || sport === "run") && (
          <>
            <button
              onClick={() => inferMutation.mutate()}
              disabled={inferMutation.isPending}
              style={{
                border: "1px solid var(--line)",
                borderRadius: 8,
                background: "var(--card)",
                color: "var(--ink2)",
                fontSize: 12.5,
                fontWeight: 700,
                padding: "7px 14px",
                cursor: "pointer",
                opacity: inferMutation.isPending ? 0.6 : 1,
              }}
            >
              {inferMutation.isPending ? "Building…" : "Create workout from laps"}
            </button>
            <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12.5, color: "var(--ink3)" }}>
              <input type="checkbox" checked={autoDetectRepeats} onChange={(e) => setAutoDetectRepeats(e.target.checked)} />
              Auto-detect repeat groups
            </label>
            {inferError && <span style={{ fontSize: 12.5, color: "#e0442e" }}>{inferError}</span>}
          </>
        )}
        {workoutId && <RegenerateLapsButton activityId={activityId} />}
      </div>
      <table style={{ width: "100%", fontSize: 13, borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ textAlign: "left", color: "var(--ink3)", fontSize: 11 }}>
            <th style={{ paddingBottom: 8 }}>Lap</th>
            <th style={{ paddingBottom: 8 }}>Step</th>
            <th style={{ paddingBottom: 8 }}>Distance</th>
            <th style={{ paddingBottom: 8 }}>Time</th>
            <th style={{ paddingBottom: 8 }}>Speed</th>
            <th style={{ paddingBottom: 8 }}>Avg Power</th>
            <th style={{ paddingBottom: 8 }}>Avg HR</th>
          </tr>
        </thead>
        <tbody>
          {laps.map((lap) => {
            const target = formatStepTarget(lap);
            return (
              <tr key={lap.index} style={{ borderTop: "1px solid var(--line)" }}>
                <td style={{ padding: "8px 0" }}>
                  <span style={{ display: "inline-block", width: 24, height: 24, borderRadius: "50%", background: "var(--elev)", textAlign: "center", lineHeight: "24px", fontSize: 11 }}>
                    {lap.index}
                  </span>
                </td>
                <td>
                  {lap.step_kind ? (
                    <span style={{ display: "flex", flexDirection: "column", gap: 1 }}>
                      <span style={{ fontSize: 12.5 }}>
                        {kindLabel(lap.step_kind)}
                        {lap.repeat_index != null && (
                          <span style={{ color: "var(--ink3)" }}> · rep {lap.repeat_index}</span>
                        )}
                      </span>
                      {target && <span className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>{target}</span>}
                    </span>
                  ) : (
                    <span style={{ fontSize: 12.5, color: "var(--ink3)" }}>—</span>
                  )}
                </td>
                <td className="mono">{lap.distance_km.toFixed(2)} km</td>
                <td className="mono">{formatDuration(lap.duration)}</td>
                <td className="mono">{((lap.distance_km / (lap.duration / 3600)) || 0).toFixed(1)} km/h</td>
                <td>
                  {lap.avg_power != null ? (
                    <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                      <div style={{ width: 60, height: 6, background: "var(--elev)", borderRadius: 3 }}>
                        <div style={{ width: `${(lap.avg_power / maxPower) * 100}%`, height: "100%", background: "var(--ember)", borderRadius: 3 }} />
                      </div>
                      <span className="mono">{lap.avg_power} w</span>
                    </div>
                  ) : (
                    <span className="mono" style={{ color: "var(--ink3)" }}>—</span>
                  )}
                </td>
                <td className="mono">{lap.avg_hr != null ? `${lap.avg_hr} bpm` : "—"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
