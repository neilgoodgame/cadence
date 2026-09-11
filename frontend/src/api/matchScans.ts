import { apiFetchWithHeaders } from "./client";
import type { WorkoutMatchScan } from "./types";

export function createWorkoutMatchScan(workoutId: string): Promise<{ data: WorkoutMatchScan; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<WorkoutMatchScan>(`/v1/workouts/${workoutId}/match-scans`, { method: "POST" });
}

export function getWorkoutMatchScan(
  workoutId: string,
  scanId: string,
): Promise<{ data: WorkoutMatchScan; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<WorkoutMatchScan>(`/v1/workouts/${workoutId}/match-scans/${scanId}`);
}
