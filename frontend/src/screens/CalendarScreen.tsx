import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getCalendar, unscheduleWorkout } from "../api/scheduling";
import { listWorkouts } from "../api/workouts";
import type { ScheduledWorkout } from "../api/types";
import { dateKey, derivedStatus, monthGridDays } from "../lib/calendar";
import { sportColor } from "../lib/sportColors";
import { ScheduleModal } from "./calendar/ScheduleModal";

const WEEKDAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

function monthRangeISO(year: number, month: number): { from: string; to: string } {
  const from = new Date(year, month, 1);
  const to = new Date(year, month + 1, 0);
  return { from: from.toISOString().slice(0, 10), to: to.toISOString().slice(0, 10) };
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
    return map;
  }, [calendarData]);

  const days = monthGridDays(year, month);
  const todayKey = dateKey(today);

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

      <div style={{ display: "grid", gridTemplateColumns: "repeat(7, 1fr)", gap: 1, background: "var(--line)", border: "1px solid var(--line)", borderRadius: 10, overflow: "hidden" }}>
        {WEEKDAY_LABELS.map((label) => (
          <div key={label} style={{ background: "var(--elev)", padding: "8px 10px", fontSize: 11, fontWeight: 600, color: "var(--ink3)" }}>
            {label}
          </div>
        ))}
        {days.map((day) => {
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
                    {workout?.name ?? entry.workout_id}
                  </div>
                );
              })}
            </div>
          );
        })}
      </div>

      {scheduleDate && <ScheduleModal date={scheduleDate} onClose={() => setScheduleDate(null)} />}
    </div>
  );
}
