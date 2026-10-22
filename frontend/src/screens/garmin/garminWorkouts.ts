import type { QueryClient } from "@tanstack/react-query";
import { queueGarminWorkoutOp } from "../../api/garmin";
import type { Athlete, WorkoutSport } from "../../api/types";
import type { Step } from "../workouts/workoutTree";
import { buildTcx, thresholdsFromAthlete } from "../workouts/workoutExport";

/** Queue a push of this workout (as a Garmin .tcx built from the athlete's thresholds). */
export async function queueWorkoutPush(
  workoutId: string,
  name: string,
  sport: WorkoutSport,
  steps: Step[],
  user: Athlete | null,
): Promise<void> {
  await queueGarminWorkoutOp({
    kind: "upsert_workout",
    workout_id: workoutId,
    name,
    tcx: buildTcx(name, sport, steps, thresholdsFromAthlete(user)),
  });
}

/** Everything a Garmin-linked view caches: refresh it after queueing something. */
export function refreshGarminQueries(queryClient: QueryClient): void {
  queryClient.invalidateQueries({ queryKey: ["garmin-workout-links"] });
  queryClient.invalidateQueries({ queryKey: ["garmin-workout-ops"] });
}
