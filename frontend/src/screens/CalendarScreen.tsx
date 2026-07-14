import { Fragment, useMemo, useState } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getActivity } from "../api/activities";
import { getCalendar, unscheduleWorkout } from "../api/scheduling";
import { listWorkouts } from "../api/workouts";
import type { Activity, ScheduledWorkout, Workout } from "../api/types";
import { dateKey, derivedStatus, monthGridDays } from "../lib/calendar";
import { formatDuration } from "../lib/format";
import { sportColor } from "../lib/sportColors";
import { ScheduleModal } from "./calendar/ScheduleModal";

const WEEKDAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

function monthRangeISO(year: number, month: number): { from: string; to: string } {
  const from = new Date(year, month, 1);
  const to = new Date(year, month + 1, 0);
  return { from: from.toISOString().slice(0, 10), to: to.toISOString().slice(0, 10) };
}

function formatHours(totalSeconds: number): string {
  return `${(totalSeconds / 3600).toFixed(1)}h`;
}

function activityStatsLine(activity: Activity): string {
  const tss = `${Math.round(activity.tss)} TSS`;
  return activity.distance_km > 0 ? `${activity.distance_km.toFixed(1)} km · ${tss}` : `${formatDuration(activity.moving_time)} · ${tss}`;
}

function workoutStatsLine(workout: Workout): string {
  return `${formatDuration(workout.duration)} · ${workout.tss} TSS`;
}

const entryStatsStyle = { fontSize: 10, color: "var(--ink3)", marginTop: 1 } as const;

// Unset (Python sends "", Java sends null) sinks below timed entries.
const TIME_OF_DAY_ORDER: Record<string, number> = { AM: 0, MID: 1, PM: 2 };

function timeOfDayRank(entry: ScheduledWorkout): number {
  return TIME_OF_DAY_ORDER[entry.time_of_day ?? ""] ?? 3;
}

function TimeOfDayBadge({ entry }: { entry: ScheduledWorkout }) {
  if (!entry.time_of_day) {
    return null;
  }
  return (
    <span
      className="mono"
      style={{ fontSize: 9, fontWeight: 700, letterSpacing: "0.05em", color: "var(--ink3)", border: "1px solid var(--line)", borderRadius: 4, padding: "0 3px", marginRight: 5, verticalAlign: "1px" }}
    >
      {entry.time_of_day}
    </span>
  );
}

interface WeekTotals {
  plannedTss: number;
  plannedSecs: number;
  actualTss: number;
  actualSecs: number;
}

function WeekSummaryCell({ totals }: { totals: WeekTotals }) {
  const hasPlanned = totals.plannedTss > 0 || totals.plannedSecs > 0;
  const hasActual = totals.actualTss > 0 || totals.actualSecs > 0;
  return (
    <div style={{ background: "var(--elev)", padding: 8, display: "flex", flexDirection: "column", gap: 4, justifyContent: "flex-start" }}>
      {!hasPlanned && !hasActual && <div style={{ fontSize: 11, color: "var(--ink3)" }}>—</div>}
      {hasPlanned && (
        <div>
          <div style={{ fontSize: 9, fontWeight: 600, letterSpacing: "0.06em", color: "var(--ink3)" }}>PLANNED</div>
          <div className="mono" style={{ fontSize: 11, color: "var(--ink2)" }}>
            {Math.round(totals.plannedTss)} TSS · {formatHours(totals.plannedSecs)}
          </div>
        </div>
      )}
      {hasActual && (
        <div>
          <div style={{ fontSize: 9, fontWeight: 600, letterSpacing: "0.06em", color: "var(--ink3)" }}>ACTUAL</div>
          <div className="mono" style={{ fontSize: 11, color: "var(--ink)" }}>
            {Math.round(totals.actualTss)} TSS · {formatHours(totals.actualSecs)}
          </div>
        </div>
      )}
    </div>
  );
}

export function CalendarScreen() {
  const today = new Date();
  const [year, setYear] = useState(today.getFullYear());
  const [month, setMonth] = useState(today.getMonth());
  const [scheduleDate, setScheduleDate] = useState<string | null>(null);

  const queryClient = useQueryClient();
  const { from, to } = monthRangeISO(year, month);
  const { data: calendarData } = useQuery({ queryKey: ["calendar", from, to], queryFn: () => getCalendar(from, to) });
  const { data: workoutsData } = useQuery({ queryKey: ["workouts"], queryFn: listWorkouts });

  const unscheduleMutation = useMutation({
    mutationFn: unscheduleWorkout,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["calendar"] }),
  });

  const workoutById = useMemo(() => {
    const map = new Map(workoutsData?.data.map((w) => [w.id, w]));
    return map;
  }, [workoutsData]);

  const entriesByDate = useMemo(() => {
    const map = new Map<string, ScheduledWorkout[]>();
    for (const entry of calendarData?.data ?? []) {
      const list = map.get(entry.date) ?? [];
      list.push(entry);
      map.set(entry.date, list);
    }
    for (const list of map.values()) {
      list.sort((a, b) => timeOfDayRank(a) - timeOfDayRank(b));
    }
    return map;
  }, [calendarData]);

  const unplannedByDate = useMemo(() => {
    const map = new Map<string, Activity[]>();
    for (const activity of calendarData?.unplanned_activities ?? []) {
      const key = dateKey(new Date(activity.start_date));
      const list = map.get(key) ?? [];
      list.push(activity);
      map.set(key, list);
    }
    return map;
  }, [calendarData]);

  // The calendar response only carries the matched activity's id (unplanned_activities
  // excludes anything linked to a scheduled workout), so the actual numbers for completed
  // entries need a fetch per activity. Shares the ["activity", id] cache with the
  // analysis screen, so clicking through is instant and revisits are free.
  const matchedActivityIds = useMemo(
    () => (calendarData?.data ?? []).flatMap((entry) => (entry.activity_id ? [entry.activity_id] : [])),
    [calendarData],
  );
  const activityById = useQueries({
    queries: matchedActivityIds.map((id) => ({ queryKey: ["activity", id], queryFn: () => getActivity(id) })),
    combine: (results) => new Map(results.flatMap((r) => (r.data ? [[r.data.id, r.data] as const] : []))),
  });

  const days = monthGridDays(year, month);
  const weeks: Date[][] = [];
  for (let i = 0; i < days.length; i += 7) {
    weeks.push(days.slice(i, i + 7));
  }
  const todayKey = dateKey(today);

  function weekTotals(week: Date[]): WeekTotals {
    const totals: WeekTotals = { plannedTss: 0, plannedSecs: 0, actualTss: 0, actualSecs: 0 };
    for (const day of week) {
      const key = dateKey(day);
      for (const entry of entriesByDate.get(key) ?? []) {
        const workout = workoutById.get(entry.workout_id);
        if (workout) {
          totals.plannedTss += workout.tss;
          totals.plannedSecs += workout.duration;
        }
        const activity = entry.activity_id ? activityById.get(entry.activity_id) : undefined;
        if (activity) {
          totals.actualTss += activity.tss;
          totals.actualSecs += activity.moving_time;
        }
      }
      for (const activity of unplannedByDate.get(key) ?? []) {
        totals.actualTss += activity.tss;
        totals.actualSecs += activity.moving_time;
      }
    }
    return totals;
  }

  function goToPreviousMonth() {
    const d = new Date(year, month - 1, 1);
    setYear(d.getFullYear());
    setMonth(d.getMonth());
  }
  function goToNextMonth() {
    const d = new Date(year, month + 1, 1);
    setYear(d.getFullYear());
    setMonth(d.getMonth());
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>
          {new Date(year, month, 1).toLocaleDateString(undefined, { month: "long", year: "numeric" })}
        </h1>
        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <button onClick={goToPreviousMonth} style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, padding: "6px 10px" }}>
            ‹
          </button>
          <button
            onClick={() => {
              setYear(today.getFullYear());
              setMonth(today.getMonth());
            }}
            style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, padding: "6px 12px", fontSize: 13, fontWeight: 600 }}
          >
            Today
          </button>
          <button onClick={goToNextMonth} style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, padding: "6px 10px" }}>
            ›
          </button>
          <button
            onClick={() => setScheduleDate(todayKey)}
            style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", marginLeft: 8 }}
          >
            Schedule workout
          </button>
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(7, 1fr) minmax(110px, 0.7fr)", gap: 1, background: "var(--line)", border: "1px solid var(--line)", borderRadius: 10, overflow: "hidden" }}>
        {WEEKDAY_LABELS.map((label) => (
          <div key={label} style={{ background: "var(--elev)", padding: "8px 10px", fontSize: 11, fontWeight: 600, color: "var(--ink3)" }}>
            {label}
          </div>
        ))}
        <div style={{ background: "var(--elev)", padding: "8px 10px", fontSize: 11, fontWeight: 600, color: "var(--ink3)" }}>Week</div>
        {weeks.map((week) => (
          <Fragment key={dateKey(week[0])}>
            {week.map((day) => {
              const key = dateKey(day);
              const isOutOfMonth = day.getMonth() !== month;
              const entries = entriesByDate.get(key) ?? [];
              return (
                <div
                  key={key}
                  onClick={() => setScheduleDate(key)}
                  style={{
                    background: "var(--card)",
                    padding: 8,
                    minHeight: 96,
                    opacity: isOutOfMonth ? 0.55 : 1,
                    cursor: "pointer",
                    display: "flex",
                    flexDirection: "column",
                    gap: 4,
                  }}
                >
                  <div style={{ fontSize: 12, fontWeight: key === todayKey ? 800 : 600, color: key === todayKey ? "var(--ember)" : "var(--ink2)" }}>
                    {day.getDate()}
                  </div>
                  {entries.map((entry) => {
                    const workout = workoutById.get(entry.workout_id);
                    const status = derivedStatus(entry, today);
                    const color = workout ? sportColor(workout.sport) : "var(--ink3)";
                    const isCompleted = status === "completed";
                    if (isCompleted && entry.activity_id) {
                      const activity = activityById.get(entry.activity_id);
                      return (
                        <Link
                          key={entry.id}
                          to={`/activities/${entry.activity_id}`}
                          onClick={(e) => e.stopPropagation()}
                          style={{
                            fontSize: 11,
                            padding: "3px 6px",
                            borderRadius: 6,
                            borderLeft: `3px solid ${color}`,
                            background: `${color}22`,
                            color: "var(--ink)",
                            textDecoration: "none",
                            display: "block",
                          }}
                        >
                          <TimeOfDayBadge entry={entry} />
                          {workout?.name ?? entry.workout_id}
                          {activity && (
                            <div className="mono" style={entryStatsStyle}>
                              {activityStatsLine(activity)}
                            </div>
                          )}
                        </Link>
                      );
                    }
                    return (
                      <div
                        key={entry.id}
                        onClick={(e) => {
                          e.stopPropagation();
                          if (!isCompleted && confirm(`Unschedule ${workout?.name ?? "this workout"}?`)) {
                            unscheduleMutation.mutate(entry.id);
                          }
                        }}
                        style={{
                          fontSize: 11,
                          padding: "3px 6px",
                          borderRadius: 6,
                          borderLeft: `3px solid ${color}`,
                          borderTop: isCompleted ? "none" : `1px dashed ${color}88`,
                          borderRight: isCompleted ? "none" : `1px dashed ${color}88`,
                          borderBottom: isCompleted ? "none" : `1px dashed ${color}88`,
                          background: isCompleted ? `${color}22` : "transparent",
                          color: status === "missed" ? "#e0442e" : isCompleted ? "var(--ink)" : "var(--ink2)",
                        }}
                      >
                        <TimeOfDayBadge entry={entry} />
                        {workout?.name ?? entry.workout_id}
                        {workout && (
                          <div className="mono" style={entryStatsStyle}>
                            {workoutStatsLine(workout)}
                          </div>
                        )}
                      </div>
                    );
                  })}
                  {(unplannedByDate.get(key) ?? []).map((activity) => {
                    const color = sportColor(activity.sport);
                    return (
                      <Link
                        key={activity.id}
                        to={`/activities/${activity.id}`}
                        onClick={(e) => e.stopPropagation()}
                        style={{
                          fontSize: 11,
                          padding: "3px 6px",
                          borderRadius: 6,
                          borderLeft: `3px solid ${color}`,
                          background: `${color}22`,
                          color: "var(--ink)",
                          textDecoration: "none",
                          display: "block",
                        }}
                      >
                        {activity.name}
                        <div className="mono" style={entryStatsStyle}>
                          {activityStatsLine(activity)}
                        </div>
                      </Link>
                    );
                  })}
                </div>
              );
            })}
            <WeekSummaryCell totals={weekTotals(week)} />
          </Fragment>
        ))}
      </div>

      {scheduleDate && <ScheduleModal date={scheduleDate} onClose={() => setScheduleDate(null)} />}
    </div>
  );
}
