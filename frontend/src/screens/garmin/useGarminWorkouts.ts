import { useQuery } from "@tanstack/react-query";
import { getGarminConnection, getGarminWorkoutLinks } from "../../api/garmin";

function isExpired(date: string | null): boolean {
  return !!date && date < new Date().toISOString().slice(0, 10);
}

/** Whether the Garmin push options should be offered (a usable Garmin connection), and what is
 * already on Garmin Connect. */
export function useGarminWorkouts() {
  const { data: connection } = useQuery({ queryKey: ["garmin-connection"], queryFn: getGarminConnection });
  const available = connection?.connected === true && !isExpired(connection.key_expires_on);
  const { data: links } = useQuery({
    queryKey: ["garmin-workout-links"],
    queryFn: getGarminWorkoutLinks,
    enabled: available,
  });
  return {
    available,
    pending: links?.pending ?? 0,
    failed: links?.failed ?? 0,
    isWorkoutOnGarmin: (workoutId: string | null | undefined) =>
      !!workoutId && !!links?.workouts.some((w) => w.workout_id === workoutId),
    isScheduleOnGarmin: (scheduledId: string) => !!links?.schedules.some((s) => s.scheduled_workout_id === scheduledId),
  };
}
