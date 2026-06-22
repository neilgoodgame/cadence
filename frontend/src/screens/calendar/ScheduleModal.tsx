import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { scheduleWorkout } from "../../api/scheduling";
import { listWorkouts } from "../../api/workouts";
import { useAuth } from "../../auth/AuthContext";
import { formatDuration } from "../../lib/format";
import type { TimeOfDay } from "../../api/types";

const TIME_OPTIONS: { value: TimeOfDay; label: string }[] = [
  { value: "AM", label: "Morning" },
  { value: "MID", label: "Midday" },
  { value: "PM", label: "Evening" },
];

export function ScheduleModal({ date, onClose }: { date: string; onClose: () => void }) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: ["workouts"], queryFn: listWorkouts });
  const [workoutId, setWorkoutId] = useState("");
  const [scheduleDate, setScheduleDate] = useState(date);
  const [timeOfDay, setTimeOfDay] = useState<TimeOfDay | "">("");

  const mutation = useMutation({
    mutationFn: () =>
      scheduleWorkout({
        workout_id: workoutId,
        athlete_id: user!.id,
        date: scheduleDate,
        time_of_day: timeOfDay || undefined,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["calendar"] });
      onClose();
    },
  });

  const workouts = data?.data ?? [];

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ background: "var(--card)", borderRadius: 14, padding: 24, width: 420, display: "flex", flexDirection: "column", gap: 16 }}
      >
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Schedule a workout</h2>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Workout</div>
          <select
            value={workoutId}
            onChange={(e) => setWorkoutId(e.target.value)}
            style={{ width: "100%", padding: "8px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)" }}
          >
            <option value="">Choose a workout…</option>
            {workouts.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name} · {formatDuration(w.duration)} · {w.tss} TSS
              </option>
            ))}
          </select>
        </label>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Date</div>
          <input
            type="date"
            value={scheduleDate}
            onChange={(e) => setScheduleDate(e.target.value)}
            style={{ width: "100%", padding: "8px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)" }}
          />
        </label>

        <div>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Time of day (optional)</div>
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

        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
          <button onClick={onClose} style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, padding: "8px 16px" }}>
            Cancel
          </button>
          <button
            onClick={() => mutation.mutate()}
            disabled={!workoutId || !scheduleDate || mutation.isPending}
            style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px" }}
          >
            Add to calendar
          </button>
        </div>
      </div>
    </div>
  );
}
