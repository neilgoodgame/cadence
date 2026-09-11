import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { getWorkout, getWorkoutMatches } from "../../api/workouts";
import { getCalendar } from "../../api/scheduling";
import type { WorkoutDetail } from "../../api/types";
import { Card } from "../../components/Card";
import { LinkedActivitiesList, type LinkedActivityRowData } from "../../components/LinkedActivityRow";
import { dateKey } from "../../lib/calendar";
import { formatDate, formatDuration, formatKeyMetric } from "../../lib/format";
import { sportColor, sportLabel } from "../../lib/sportColors";
import { MiniChart } from "./WorkoutLibraryScreen";
import { SummaryStat, WorkoutEditor } from "./WorkoutEditor";
import { WorkoutMatchScanCard } from "./WorkoutMatchScanCard";
import { WorkoutStructureList } from "./WorkoutStructureList";
import { NOOP_STRUCTURE_ACTIONS, withIds } from "./workoutTree";

// Wide enough that a lightly-scheduled workout still shows its next few placements, matching
// UpcomingWorkoutsCard's own reasoning for the dashboard's athlete-wide equivalent.
const UPCOMING_LOOKAHEAD_DAYS = 180;

export function WorkoutDetailScreen() {
  const { id } = useParams<{ id: string }>();
  const [mode, setMode] = useState<"view" | "edit">("view");

  const workoutQuery = useQuery({ queryKey: ["workout", id], queryFn: () => getWorkout(id!), enabled: !!id });

  if (workoutQuery.isError) {
    return (
      <div style={{ color: "var(--ink3)" }}>
        Couldn't load this workout - it may have been removed.{" "}
        <Link to="/workouts" style={{ color: "var(--ember)", fontWeight: 600 }}>
          Back to library
        </Link>
      </div>
    );
  }

  if (!workoutQuery.data) {
    return <div style={{ color: "var(--ink3)" }}>Loading…</div>;
  }

  if (mode === "edit") {
    return <WorkoutEditor workoutId={id!} onDone={() => setMode("view")} />;
  }

  return <WorkoutDetailView workout={workoutQuery.data} onEdit={() => setMode("edit")} />;
}

function WorkoutDetailView({ workout, onEdit }: { workout: WorkoutDetail; onEdit: () => void }) {
  const navigate = useNavigate();
  const structure = useMemo(() => withIds(workout.steps), [workout]);

  const matchesQuery = useQuery({ queryKey: ["workout-matches", workout.id], queryFn: () => getWorkoutMatches(workout.id, "all") });
  const linked: LinkedActivityRowData[] =
    matchesQuery.data?.data.map((m) => ({ id: m.activity_id, date: formatDate(m.date), name: m.name, metric: formatKeyMetric(m), tss: m.tss })) ?? [];

  const { from, to } = useMemo(() => {
    const start = new Date();
    const end = new Date();
    end.setDate(end.getDate() + UPCOMING_LOOKAHEAD_DAYS);
    return { from: dateKey(start), to: dateKey(end) };
  }, []);
  const calendarQuery = useQuery({ queryKey: ["calendar", from, to], queryFn: () => getCalendar(from, to) });
  const upcoming = (calendarQuery.data?.data ?? [])
    .filter((entry) => entry.workout_id === workout.id && entry.status === "planned")
    .sort((a, b) => a.date.localeCompare(b.date));

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20, maxWidth: 640 }}>
      <div>
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
          <div
            style={{
              display: "inline-flex",
              padding: "3px 9px",
              borderRadius: 6,
              fontFamily: "monospace",
              fontSize: 10.5,
              fontWeight: 700,
              background: `${sportColor(workout.sport)}22`,
              color: sportColor(workout.sport),
            }}
          >
            {sportLabel(workout.sport)}
          </div>
          <button
            onClick={onEdit}
            style={{ border: "1px solid var(--line)", borderRadius: 8, padding: "6px 14px", fontSize: 12.5, fontWeight: 600, background: "var(--card)", color: "var(--ink2)", cursor: "pointer" }}
          >
            Edit
          </button>
        </div>
        <h1 style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.02em", margin: "8px 0 12px" }}>{workout.name}</h1>
        <div style={{ display: "flex", gap: 22 }}>
          <SummaryStat label="DURATION" value={formatDuration(workout.duration)} />
          <SummaryStat label="EST. TSS" value={String(workout.tss)} />
          <SummaryStat label="STEPS" value={String(workout.steps.length)} />
        </div>
        <MiniChart preview={workout.chart_preview} />
      </div>

      <Card>
        <WorkoutStructureList steps={structure} actions={NOOP_STRUCTURE_ACTIONS} readOnly sport={workout.sport} />
      </Card>

      <Card>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 12 }}>
          <div className="mono" style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", color: "var(--ink3)" }}>
            LINKED ACTIVITIES
          </div>
          {linked.length >= 2 && (
            <Link to={`/workouts/${workout.id}/compare`} style={{ fontSize: 12.5, fontWeight: 600, color: "var(--ember)" }}>
              Compare all →
            </Link>
          )}
        </div>
        {linked.length === 0 ? (
          <div style={{ fontSize: 13, color: "var(--ink3)" }}>No activities linked to this workout yet.</div>
        ) : (
          <LinkedActivitiesList activities={linked} />
        )}
      </Card>

      <Card>
        <div className="mono" style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", color: "var(--ink3)", marginBottom: 12 }}>
          UPCOMING
        </div>
        {upcoming.length === 0 ? (
          <div style={{ fontSize: 13, color: "var(--ink3)" }}>Not currently scheduled.</div>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            {upcoming.map((entry) => (
              <div
                key={entry.id}
                onClick={() => navigate(`/scheduled/${entry.id}`)}
                style={{ display: "flex", alignItems: "center", gap: 12, padding: "9px 12px", borderRadius: 9, background: "var(--elev)", border: "1px solid var(--line)", cursor: "pointer" }}
              >
                <span className="mono" style={{ fontSize: 12, fontWeight: 600, color: "var(--ink)" }}>
                  {formatDate(entry.date, true)}
                </span>
                {entry.time_of_day && (
                  <span className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>
                    {entry.time_of_day}
                  </span>
                )}
              </div>
            ))}
          </div>
        )}
      </Card>

      <WorkoutMatchScanCard workoutId={workout.id} steps={workout.steps} />
    </div>
  );
}
