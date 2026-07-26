import { apiFetch } from "./client";
import type {
  DataList,
  Workout,
  WorkoutDetail,
  WorkoutFolder,
  WorkoutMatch,
  WorkoutMatchMethod,
  WorkoutSort,
  WorkoutSport,
  WorkoutStep,
} from "./types";

export interface ListWorkoutsParams {
  folder_id?: string;
  tag?: string;
  sport?: WorkoutSport;
  search?: string;
  sort?: WorkoutSort;
}

export function listWorkouts(params: ListWorkoutsParams = {}): Promise<DataList<Workout>> {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value) query.set(key, value);
  }
  const qs = query.toString();
  return apiFetch<DataList<Workout>>(`/v1/workouts${qs ? `?${qs}` : ""}`);
}

export function getWorkout(id: string): Promise<WorkoutDetail> {
  return apiFetch<WorkoutDetail>(`/v1/workouts/${id}`);
}

export interface WorkoutInput {
  name: string;
  sport: WorkoutSport;
  steps: WorkoutStep[];
  folder_id?: string | null;
  tags?: string[];
}

export function createWorkout(input: WorkoutInput): Promise<Workout> {
  return apiFetch<Workout>("/v1/workouts", { method: "POST", body: input });
}

export function updateWorkout(
  id: string,
  input: { name?: string; steps?: WorkoutStep[]; folder_id?: string | null; tags?: string[] },
): Promise<Workout> {
  return apiFetch<Workout>(`/v1/workouts/${id}`, { method: "PATCH", body: input });
}

export function deleteWorkout(id: string): Promise<void> {
  return apiFetch<void>(`/v1/workouts/${id}`, { method: "DELETE" });
}

export function getWorkoutMatches(id: string, method: WorkoutMatchMethod | "all" = "all"): Promise<DataList<WorkoutMatch>> {
  return apiFetch<DataList<WorkoutMatch>>(`/v1/workouts/${id}/matches?method=${method}`);
}

export function listWorkoutFolders(): Promise<DataList<WorkoutFolder>> {
  return apiFetch<DataList<WorkoutFolder>>("/v1/workout-folders");
}

export function createWorkoutFolder(name: string): Promise<WorkoutFolder> {
  return apiFetch<WorkoutFolder>("/v1/workout-folders", { method: "POST", body: { name } });
}

export function updateWorkoutFolder(id: string, name: string): Promise<WorkoutFolder> {
  return apiFetch<WorkoutFolder>(`/v1/workout-folders/${id}`, { method: "PATCH", body: { name } });
}

export function deleteWorkoutFolder(id: string): Promise<void> {
  return apiFetch<void>(`/v1/workout-folders/${id}`, { method: "DELETE" });
}
