import { apiFetchWithHeaders } from "./client";
import type { MatchScanCorrelationBasis, MatchScanDurationBasis, StepKind, WorkoutMatchScan } from "./types";

export function createWorkoutMatchScan(
  workoutId: string,
  excludedStepKinds: StepKind[],
  durationBasis: MatchScanDurationBasis = "time",
  smoothPower: boolean = false,
  correlationBasis: MatchScanCorrelationBasis = "power",
): Promise<{ data: WorkoutMatchScan; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<WorkoutMatchScan>(`/v1/workouts/${workoutId}/match-scans`, {
    method: "POST",
    body: {
      excluded_step_kinds: excludedStepKinds,
      duration_basis: durationBasis,
      smooth_power: smoothPower,
      correlation_basis: correlationBasis,
    },
  });
}

export function getWorkoutMatchScan(
  workoutId: string,
  scanId: string,
): Promise<{ data: WorkoutMatchScan; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<WorkoutMatchScan>(`/v1/workouts/${workoutId}/match-scans/${scanId}`);
}
