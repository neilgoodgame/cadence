import { apiFetch } from "./client";
import type { DataList, Workout, WorkoutDetail, WorkoutMatch, WorkoutMatchMethod, WorkoutSport, WorkoutStep } from "./types";

export function listWorkouts(): Promise<DataList<Workout>> {
  return apiFetch<DataList<Workout>>("/v1/workouts");
}

export function getWorkout(id: string): Promise<WorkoutDetail> {
  return apiFetch<WorkoutDetail>(`/v1/workouts/${id}`);
}

export interface WorkoutInput {
  name: string;
  sport: WorkoutSport;
  steps: WorkoutStep[];
}

export function createWorkout(input: WorkoutInput): Promise<Workout> {
  return apiFetch<Workout>("/v1/workouts", { method: "POST", body: input });
}

export function updateWorkout(id: string, input: { name?: string; steps?: WorkoutStep[] }): Promise<Workout> {
  return apiFetch<Workout>(`/v1/workouts/${id}`, { method: "PATCH", body: input });
}

export function deleteWorkout(id: string): Promise<void> {
  return apiFetch<void>(`/v1/workouts/${id}`, { method: "DELETE" });
}

export function getWorkoutMatches(id: string, method: WorkoutMatchMethod | "all" = "all"): Promise<DataList<WorkoutMatch>> {
  return apiFetch<DataList<WorkoutMatch>>(`/v1/workouts/${id}/matches?method=${method}`);
}
