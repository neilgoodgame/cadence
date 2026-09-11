import { apiFetchWithHeaders } from "./client";
import type { StepKind, WorkoutMatchScan } from "./types";

export function createWorkoutMatchScan(
  workoutId: string,
  excludedStepKinds: StepKind[],
): Promise<{ data: WorkoutMatchScan; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<WorkoutMatchScan>(`/v1/workouts/${workoutId}/match-scans`, {
    method: "POST",
    body: { excluded_step_kinds: excludedStepKinds },
  });
}

export function getWorkoutMatchScan(
  workoutId: string,
  scanId: string,
): Promise<{ data: WorkoutMatchScan; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<WorkoutMatchScan>(`/v1/workouts/${workoutId}/match-scans/${scanId}`);
}
