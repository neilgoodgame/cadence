import { useState } from "react";
import type { Activity, Race, RaceSplitsHalves } from "../../api/types";
import { formatDuration, formatPace, parseDuration } from "../../lib/format";
import { InlineGoalEditor } from "./InlineGoalEditor";

function halvesLabel(halves: RaceSplitsHalves): string {
  const diff = halves.second_s - halves.first_s;
  if (Math.abs(diff) < 5) return "Even split";
  return diff < 0 ? "Negative split" : "Positive split";
}

/** README §4.2.3 "Goal / Result" card - one of the three auto-fit-grid cards. */
export function GoalResultCard({
  race,
  pbS,
  predictionS,
  halves,
  activity,
}: {
  race: Race;
  pbS: number | null;
  predictionS: number | null;
  halves: RaceSplitsHalves | null;
  activity: Activity | null;
}) {
  const [editing, setEditing] = useState(false);

  const resultS = race.result_time ? parseDuration(race.result_time) : null;
  const goalS = race.goal_time ? parseDuration(race.goal_time) : null;

  if (editing) {
    return (
      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 18, minWidth: 290 }}>
        <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", marginBottom: 10 }}>
          Goal
        </div>
        <InlineGoalEditor
          raceId={race.id}
          distanceKm={race.distance_km}
          currentGoal={race.goal_time}
          pbS={pbS}
          predictionS={predictionS}
          onClose={() => setEditing(false)}
        />
      </div>
    );
  }

  if (!resultS) {
    const pace = goalS != null && race.distance_km ? formatPace(goalS / race.distance_km) : null;
    const vsPb = goalS != null && pbS != null ? goalS - pbS : null;

    return (
      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 18, minWidth: 290 }}>
        <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", marginBottom: 10 }}>
          Goal
        </div>
        {goalS != null ? (
          <>
            <div className="mono" style={{ fontSize: 34, fontWeight: 800 }}>{formatDuration(goalS)}</div>
            <div style={{ fontSize: 12.5, color: "var(--ink3)", marginTop: 4 }}>
              {pace}
              {vsPb != null && ` · ${vsPb <= 0 ? "-" : "+"}${formatDuration(Math.abs(vsPb))} vs PB`}
            </div>
          </>
        ) : (
          <div style={{ fontSize: 13, color: "var(--ink3)" }}>No goal set yet.</div>
        )}
        {predictionS != null && (
          <div style={{ fontSize: 12.5, color: "var(--ink2)", marginTop: 8 }}>
            Predicted: <span className="mono" style={{ fontWeight: 700 }}>{formatDuration(predictionS)}</span>
          </div>
        )}
        <button
          onClick={() => setEditing(true)}
          style={{ marginTop: 12, border: "1px solid var(--line)", background: "none", borderRadius: 8, fontSize: 12.5, fontWeight: 600, padding: "5px 12px", cursor: "pointer", color: "var(--ink2)" }}
        >
          {goalS != null ? "Edit" : "Set goal"}
        </button>
      </div>
    );
  }

  const vsGoal = goalS != null ? resultS - goalS : null;
  const pace = race.distance_km ? formatPace(resultS / race.distance_km) : null;

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 18, minWidth: 290 }}>
      <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", marginBottom: 10 }}>
        Result
      </div>
      <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
        <div className="mono" style={{ fontSize: 34, fontWeight: 800 }}>{formatDuration(resultS)}</div>
        {vsGoal != null && (
          <span
            style={{
              fontSize: 12,
              fontWeight: 700,
              padding: "2px 9px",
              borderRadius: 20,
              color: vsGoal <= 0 ? "#2fa66a" : "#e0442e",
              border: `1px solid ${vsGoal <= 0 ? "#2fa66a" : "#e0442e"}`,
            }}
          >
            {vsGoal <= 0 ? "-" : "+"}{formatDuration(Math.abs(vsGoal))} vs goal
          </span>
        )}
      </div>
      <div style={{ fontSize: 12.5, color: "var(--ink3)", marginTop: 6 }}>
        {[
          pace,
          activity?.avg_hr != null && `${activity.avg_hr} bpm`,
          activity?.avg_power != null && `${activity.avg_power} W`,
          activity?.avg_air_temp != null && `${activity.avg_air_temp.toFixed(0)}°C`,
          activity?.avg_humidity != null && `${activity.avg_humidity.toFixed(0)}% hum`,
        ].filter(Boolean).join(" · ")}
      </div>

      {halves && (
        <div style={{ fontSize: 12.5, color: "var(--ink2)", marginTop: 10 }}>
          {halvesLabel(halves)}: {formatDuration(halves.first_s)} / {formatDuration(halves.second_s)}
        </div>
      )}

      <div style={{ fontSize: 12.5, color: "var(--ink3)", marginTop: 8, display: "flex", alignItems: "center", gap: 6 }}>
        Goal {goalS != null ? formatDuration(goalS) : "–"}
        <button
          onClick={() => setEditing(true)}
          style={{ border: "none", background: "none", color: "var(--ember)", fontSize: 12, fontWeight: 600, cursor: "pointer", padding: 0 }}
        >
          Edit
        </button>
      </div>

      {predictionS != null && (
        <div style={{ fontSize: 12.5, color: "var(--ink2)", marginTop: 6 }}>
          Predicted <span className="mono" style={{ fontWeight: 700 }}>{formatDuration(predictionS)}</span>, actual{" "}
          <span className="mono" style={{ fontWeight: 700 }}>{formatDuration(resultS)}</span>
        </div>
      )}
    </div>
  );
}
