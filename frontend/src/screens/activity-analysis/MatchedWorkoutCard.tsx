import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getWorkout, getWorkoutMatches } from "../../api/workouts";
import { LinkedActivitiesList, type LinkedActivityRowData } from "../../components/LinkedActivityRow";
import type { Activity } from "../../api/types";
import { formatDate, formatKeyMetric } from "../../lib/format";

function ChevronIcon({ open }: { open: boolean }) {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 16 16"
      fill="none"
      stroke="var(--ink3)"
      strokeWidth="2"
      style={{ transform: open ? "rotate(90deg)" : "rotate(0deg)", transition: "transform 0.2s ease", flexShrink: 0 }}
    >
      <polyline points="5,3 11,8 5,13" />
    </svg>
  );
}

export function MatchedWorkoutCard({ activity }: { activity: Activity }) {
  const [open, setOpen] = useState(false);
  const workoutId = activity.workout_id;

  const { data: workout } = useQuery({
    queryKey: ["workout", workoutId],
    queryFn: () => getWorkout(workoutId!),
    enabled: !!workoutId,
  });
  const { data: matches } = useQuery({
    queryKey: ["workout-matches", workoutId],
    queryFn: () => getWorkoutMatches(workoutId!),
    enabled: !!workoutId,
  });

  if (!workoutId) {
    return null;
  }

  const thisMatch = matches?.data.find((m) => m.activity_id === activity.id);
  const linked: LinkedActivityRowData[] =
    matches?.data
      .filter((m) => m.activity_id !== activity.id)
      .map((m) => ({ id: m.activity_id, date: formatDate(m.date), name: m.name, metric: formatKeyMetric(m), tss: m.tss })) ?? [];

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "16px 22px" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 9 }}>
        <span
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 5,
            fontSize: 11,
            fontWeight: 600,
            padding: "3px 9px",
            borderRadius: 20,
            background: "rgba(47,166,106,0.13)",
            color: "#2fa66a",
          }}
        >
          Matched
        </span>
        <span style={{ fontSize: 14, fontWeight: 700, color: "var(--ink)" }}>{workout?.name ?? "…"}</span>
        {thisMatch?.compliance != null && (
          <span style={{ fontSize: 12.5, color: "var(--ink3)" }}>· {Math.round(thisMatch.compliance * 100)}% compliant</span>
        )}
      </div>

      {linked.length > 0 && (
        <>
          <div
            onClick={() => setOpen((o) => !o)}
            style={{
              display: "flex",
              alignItems: "center",
              gap: 8,
              marginTop: 12,
              paddingTop: 12,
              borderTop: "1px solid var(--line)",
              cursor: "pointer",
            }}
          >
            <ChevronIcon open={open} />
            <div className="mono" style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", color: "var(--ink3)" }}>
              OTHER ACTIVITIES MATCHED TO THIS WORKOUT
            </div>
            <span
              className="mono"
              style={{
                fontSize: 10.5,
                fontWeight: 600,
                padding: "2px 8px",
                borderRadius: 20,
                background: "rgba(61,127,214,0.1)",
                color: "#3d7fd6",
              }}
            >
              {linked.length}
            </span>
          </div>
          {open && (
            <div style={{ marginTop: 10 }}>
              <LinkedActivitiesList activities={linked} />
            </div>
          )}
        </>
      )}
    </div>
  );
}
