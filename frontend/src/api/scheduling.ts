import { apiFetch } from "./client";
import type { DataList, ScheduledWorkout, TimeOfDay } from "./types";

export function getCalendar(from: string, to: string, athleteId?: string): Promise<DataList<ScheduledWorkout>> {
  const params = new URLSearchParams({ from, to });
  if (athleteId) params.set("athlete_id", athleteId);
  return apiFetch<DataList<ScheduledWorkout>>(`/v1/calendar?${params.toString()}`);
}

export interface ScheduleWorkoutInput {
  workout_id: string;
  athlete_id: string;
  date: string;
  time_of_day?: TimeOfDay;
}

export function scheduleWorkout(input: ScheduleWorkoutInput): Promise<ScheduledWorkout> {
  return apiFetch<ScheduledWorkout>("/v1/scheduled-workouts", { method: "POST", body: input });
}

export function unscheduleWorkout(id: string): Promise<void> {
  return apiFetch<void>(`/v1/scheduled-workouts/${id}`, { method: "DELETE" });
}
