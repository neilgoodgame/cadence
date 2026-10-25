import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { scheduleWorkout } from "../../api/scheduling";
import { getWorkout, listWorkouts } from "../../api/workouts";
import { ApiError } from "../../api/types";
import { queueGarminWorkoutOp } from "../../api/garmin";
import { GarminPushOption } from "../garmin/GarminPushOption";
import { queueWorkoutPush, refreshGarminQueries } from "../garmin/garminWorkouts";
import { useGarminWorkouts } from "../garmin/useGarminWorkouts";
import { fmtDuration, withIds } from "../workouts/workoutTree";
import { WorkoutPickerModal } from "../workouts/WorkoutPickerModal";
import { useAuth } from "../../auth/AuthContext";
import { sportColor, sportLabel } from "../../lib/sportColors";
import { tagColor, tagRgba } from "../../lib/tagColors";
import type { TimeOfDay } from "../../api/types";

const TIME_OPTIONS: { value: TimeOfDay; label: string }[] = [
  { value: "AM", label: "Morning" },
  { value: "MID", label: "Midday" },
  { value: "PM", label: "Evening" },
];

export function ScheduleModal({
  date,
  // Exclusive - the scheduled date must fall strictly before this (e.g. a Threshold
  // suggestion's expiry_date: testing on the expiry day itself is too late to keep the value
  // from dropping). Passed straight through as the backing <input type="date">'s `max`, one
  // day earlier, since that attribute is inclusive.
  maxDate,
  onClose,
}: {
  date: string;
  maxDate?: string;
  onClose: () => void;
}) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: ["workouts"], queryFn: () => listWorkouts() });
  const [workoutId, setWorkoutId] = useState("");
  const [pickerOpen, setPickerOpen] = useState(false);
  const [scheduleDate, setScheduleDate] = useState(date);
  const [timeOfDay, setTimeOfDay] = useState<TimeOfDay | "">("");
  const [notes, setNotes] = useState("");
  const garmin = useGarminWorkouts();
  // null = untouched: the box follows whether the chosen workout is already on Garmin Connect.
  const [pushDraft, setPushDraft] = useState<boolean | null>(null);
  const [garminError, setGarminError] = useState<string | null>(null);
  const addToGarmin = garmin.available && (pushDraft ?? garmin.isWorkoutOnGarmin(workoutId));

  const mutation = useMutation({
    mutationFn: async () => {
      const scheduled = await scheduleWorkout({
        workout_id: workoutId,
        athlete_id: user!.id,
        date: scheduleDate,
        time_of_day: timeOfDay || undefined,
        notes: notes.trim() || undefined,
      });
      // The workout is on the calendar either way; a failure to queue the Garmin push is reported separately.
      let queueError: string | null = null;
      if (addToGarmin) {
        try {
          if (!garmin.isWorkoutOnGarmin(workoutId)) {
            const detail = await getWorkout(workoutId);
            await queueWorkoutPush(workoutId, detail.name, detail.sport, withIds(detail.steps), user);
          }
          await queueGarminWorkoutOp({
            kind: "schedule",
            workout_id: workoutId,
            scheduled_workout_id: scheduled.id,
            date: scheduleDate,
          });
        }
        catch (err) {
          queueError = err instanceof ApiError ? err.message : "Scheduled, but couldn't queue it for Garmin.";
        }
      }
      return queueError;
    },
    onSuccess: (queueError) => {
      queryClient.invalidateQueries({ queryKey: ["calendar"] });
      refreshGarminQueries(queryClient);
      setGarminError(queueError);
      if (!queueError) onClose();
    },
  });

  const workouts = data?.data ?? [];
  const selectedWorkout = workouts.find((w) => w.id === workoutId);
  const inclusiveMax = maxDate ? new Date(`${maxDate}T00:00:00`) : null;
  if (inclusiveMax) inclusiveMax.setDate(inclusiveMax.getDate() - 1);
  const pastMax = inclusiveMax != null && scheduleDate > inclusiveMax.toISOString().slice(0, 10);

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

        <div>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Workout</div>
          {selectedWorkout ? (
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10, padding: "10px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)" }}>
              <div style={{ minWidth: 0 }}>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <div style={{ display: "inline-flex", padding: "2px 8px", borderRadius: 6, fontFamily: "monospace", fontSize: 10, fontWeight: 700, background: `${sportColor(selectedWorkout.sport)}22`, color: sportColor(selectedWorkout.sport) }}>
                    {sportLabel(selectedWorkout.sport)}
                  </div>
                  <div style={{ fontSize: 13.5, fontWeight: 700, color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{selectedWorkout.name}</div>
                </div>
                <div style={{ fontFamily: "monospace", fontSize: 11.5, color: "var(--ink3)", marginTop: 4 }}>
                  {fmtDuration(selectedWorkout.duration)} · {selectedWorkout.tss} TSS
                </div>
                {selectedWorkout.tags.length > 0 && (
                  <div style={{ display: "flex", flexWrap: "wrap", gap: 5, marginTop: 6 }}>
                    {selectedWorkout.tags.map((t) => (
                      <span
                        key={t}
                        style={{
                          display: "inline-flex",
                          alignItems: "center",
                          gap: 5,
                          fontSize: 10,
                          fontWeight: 600,
                          color: "var(--ink2)",
                          background: tagRgba(t, 0.1),
                          border: `1px solid ${tagRgba(t, 0.3)}`,
                          padding: "2px 7px 2px 5px",
                          borderRadius: 20,
                        }}
                      >
                        <span style={{ width: 5, height: 5, borderRadius: "50%", background: tagColor(t), flexShrink: 0 }} />
                        {t}
                      </span>
                    ))}
                  </div>
                )}
              </div>
              <button
                onClick={() => setPickerOpen(true)}
                style={{ flexShrink: 0, border: "1px solid var(--line)", background: "var(--card)", borderRadius: 7, fontSize: 12, fontWeight: 600, color: "var(--ink2)", padding: "6px 10px", cursor: "pointer" }}
              >
                Change
              </button>
            </div>
          ) : (
            <button
              onClick={() => setPickerOpen(true)}
              style={{ width: "100%", textAlign: "left", padding: "9px 12px", borderRadius: 8, border: "1px dashed var(--line)", background: "var(--elev)", color: "var(--ink3)", fontSize: 13, cursor: "pointer" }}
            >
              Choose a workout…
            </button>
          )}
        </div>

        {pickerOpen && (
          <WorkoutPickerModal
            onSelect={(w) => {
              setWorkoutId(w.id);
              setPickerOpen(false);
            }}
            onClose={() => setPickerOpen(false)}
          />
        )}

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Date</div>
          <input
            type="date"
            value={scheduleDate}
            max={inclusiveMax?.toISOString().slice(0, 10)}
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

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Notes (optional)</div>
          <textarea
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            maxLength={500}
            rows={2}
            placeholder="e.g. swap if it rains"
            style={{ width: "100%", padding: "8px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)", fontFamily: "inherit", resize: "vertical" }}
          />
        </label>

        {garmin.available && (
          <GarminPushOption
            checked={addToGarmin}
            onChange={setPushDraft}
            label="Also add to Garmin Connect"
            hint="Queued for garmin-sync on your computer; the workout is pushed first if it isn't on Garmin yet."
          />
        )}
        {garminError && <div style={{ fontSize: 13, color: "#e0442e" }}>{garminError}</div>}

        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
          <button onClick={onClose} style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, padding: "8px 16px" }}>
            Cancel
          </button>
          <button
            onClick={() => mutation.mutate()}
            disabled={!workoutId || !scheduleDate || pastMax || mutation.isPending || mutation.isSuccess}
            style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px" }}
          >
            Add to calendar
          </button>
        </div>
      </div>
    </div>
  );
}
