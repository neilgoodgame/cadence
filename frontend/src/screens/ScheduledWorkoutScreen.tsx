import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { getScheduledWorkout, unscheduleWorkout, updateScheduledWorkout } from "../api/scheduling";
import { getWorkout } from "../api/workouts";
import type { ScheduledWorkout, TimeOfDay, WorkoutDetail } from "../api/types";
import { Card } from "../components/Card";
import { formatDuration } from "../lib/format";
import { sportColor, sportLabel } from "../lib/sportColors";
import { withIds } from "./workouts/workoutTree";
import { MiniChart } from "./workouts/WorkoutLibraryScreen";
import { WorkoutStructureList, type StructureActions } from "./workouts/WorkoutStructureList";

// Read-only here (see WorkoutStructureList's readOnly prop) - none of these are ever called,
// they just satisfy StructureActions' shape.
const NOOP_STRUCTURE_ACTIONS: StructureActions = {
  selectedId: null,
  onSelect: () => {},
  onMoveUp: () => {},
  onMoveDown: () => {},
  onDuplicate: () => {},
  onRemove: () => {},
  onRepeatChange: () => {},
  onAddChild: () => {},
  onAddNestedGroup: () => {},
};

const TIME_OPTIONS: { value: TimeOfDay; label: string }[] = [
  { value: "AM", label: "Morning" },
  { value: "MID", label: "Midday" },
  { value: "PM", label: "Evening" },
];

const labelStyle = { fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 };
const inputStyle = { width: "100%", padding: "8px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)", fontSize: 13, boxSizing: "border-box" as const };
const primaryBtn = { alignSelf: "flex-start" as const, padding: "8px 16px", borderRadius: 8, border: "none", background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, cursor: "pointer" };
const dangerBtn = { padding: "8px 16px", borderRadius: 8, border: "1px solid var(--line)", background: "none", color: "#c4332a", fontSize: 13, fontWeight: 600, cursor: "pointer" };

/** Reachable by clicking a planned (not-yet-completed) calendar entry from either the Calendar
 * or the Dashboard's Upcoming workouts card - a completed entry keeps navigating straight to its
 * activity from both of those instead, since "preview the plan"/"reschedule" don't mean anything
 * once it's done. If this screen is still reached directly for one (a stale link), show that
 * link here instead of a broken edit form. */
export function ScheduledWorkoutScreen() {
  const { id } = useParams<{ id: string }>();

  const scheduledQuery = useQuery({
    queryKey: ["scheduled-workout", id],
    queryFn: () => getScheduledWorkout(id!),
    enabled: !!id,
  });
  const scheduled = scheduledQuery.data;

  const workoutQuery = useQuery({
    queryKey: ["workout", scheduled?.workout_id],
    queryFn: () => getWorkout(scheduled!.workout_id),
    enabled: !!scheduled,
  });
  const workout = workoutQuery.data;

  if (scheduledQuery.isError) {
    return (
      <div style={{ color: "var(--ink3)" }}>
        Couldn't load this scheduled workout - it may have been removed.{" "}
        <Link to="/calendar" style={{ color: "var(--ember)", fontWeight: 600 }}>
          Back to calendar
        </Link>
      </div>
    );
  }

  if (!scheduled || !workout) {
    return <div style={{ color: "var(--ink3)" }}>Loading…</div>;
  }

  if (scheduled.status === "completed" && scheduled.activity_id) {
    return (
      <div style={{ color: "var(--ink3)" }}>
        This workout has already been completed.{" "}
        <Link to={`/activities/${scheduled.activity_id}`} style={{ color: "var(--ember)", fontWeight: 600 }}>
          View activity →
        </Link>
      </div>
    );
  }

  return <ScheduledWorkoutForm scheduled={scheduled} workout={workout} />;
}

function ScheduledWorkoutForm({ scheduled, workout }: { scheduled: ScheduledWorkout; workout: WorkoutDetail }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [date, setDate] = useState(scheduled.date);
  const [timeOfDay, setTimeOfDay] = useState<TimeOfDay | "">(scheduled.time_of_day || "");
  const [notes, setNotes] = useState(scheduled.notes);

  const dirty = date !== scheduled.date || timeOfDay !== (scheduled.time_of_day || "") || notes !== scheduled.notes;

  // withIds generates fresh ids on every call (see workoutTree.ts's module-level counter) -
  // memoized so typing in the notes field below doesn't remount every structure row on each
  // keystroke.
  const structure = useMemo(() => withIds(workout.steps), [workout]);

  const saveMutation = useMutation({
    mutationFn: () => updateScheduledWorkout(scheduled.id, { date, time_of_day: timeOfDay || undefined, notes }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["scheduled-workout", scheduled.id] });
      queryClient.invalidateQueries({ queryKey: ["calendar"] });
    },
  });

  const unscheduleMutation = useMutation({
    mutationFn: () => unscheduleWorkout(scheduled.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["calendar"] });
      navigate(-1);
    },
  });

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20, maxWidth: 560 }}>
      <div>
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
        <h1 style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.02em", margin: "8px 0 2px" }}>{workout.name}</h1>
        {(workout.duration > 0 || workout.tss > 0) && (
          <div className="mono" style={{ fontSize: 13, color: "var(--ink3)" }}>
            {[workout.duration > 0 ? formatDuration(workout.duration) : null, workout.tss > 0 ? `${workout.tss} TSS` : null]
              .filter(Boolean)
              .join(" · ")}
          </div>
        )}
        <MiniChart preview={workout.chart_preview} />
        {scheduled.assigned_by && (
          <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 6, display: "flex", alignItems: "center", gap: 6 }}>
            Scheduled by {scheduled.assigned_by_name ?? "your coach"}
            {scheduled.assigned_by_is_virtual && (
              <span
                style={{ fontSize: 10, fontWeight: 700, color: "var(--ink3)", border: "1px solid var(--line)", borderRadius: 4, padding: "1px 5px" }}
              >
                AI
              </span>
            )}
          </div>
        )}
      </div>

      <Card>
        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          <label>
            <div style={labelStyle}>Date</div>
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={inputStyle} />
          </label>

          <div>
            <div style={labelStyle}>Time of day</div>
            <div style={{ display: "flex", gap: 6 }}>
              {TIME_OPTIONS.map((opt) => (
                <button
                  key={opt.value}
                  onClick={() => setTimeOfDay(timeOfDay === opt.value ? "" : opt.value)}
                  style={{
                    flex: 1,
                    padding: "8px 0",
                    borderRadius: 8,
                    border: "1px solid var(--line)",
                    background: timeOfDay === opt.value ? "var(--elev)" : "transparent",
                    color: timeOfDay === opt.value ? "var(--ink)" : "var(--ink2)",
                    fontSize: 13,
                    fontWeight: 600,
                  }}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          <label>
            <div style={labelStyle}>Notes</div>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              maxLength={500}
              rows={3}
              placeholder="e.g. swap if it rains"
              style={{ ...inputStyle, fontFamily: "inherit", resize: "vertical" }}
            />
          </label>

          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <button onClick={() => saveMutation.mutate()} disabled={!dirty || saveMutation.isPending} style={{ ...primaryBtn, opacity: !dirty || saveMutation.isPending ? 0.6 : 1 }}>
              {saveMutation.isPending ? "Saving…" : "Save changes"}
            </button>
            {saveMutation.isError && <span style={{ fontSize: 13, color: "#e0442e" }}>Failed to save. Try again.</span>}
          </div>
        </div>
      </Card>

      <Card>
        <div style={{ fontSize: 13, fontWeight: 700, color: "var(--ink)", marginBottom: 12 }}>Structure</div>
        <WorkoutStructureList steps={structure} actions={NOOP_STRUCTURE_ACTIONS} readOnly />
      </Card>

      <button
        onClick={() => confirm(`Unschedule ${workout.name}?`) && unscheduleMutation.mutate()}
        disabled={unscheduleMutation.isPending}
        style={{ ...dangerBtn, alignSelf: "flex-start", opacity: unscheduleMutation.isPending ? 0.6 : 1 }}
      >
        {unscheduleMutation.isPending ? "Unscheduling…" : "Unschedule"}
      </button>
    </div>
  );
}
