import type { ScheduledWorkout, ScheduledWorkoutStatus } from "../api/types";

// Built from local date components, not toISOString() - that converts through UTC, which
// shifts the date backward by a day for any positive UTC offset (confirmed: midnight local
// in UTC+1 is 23:00 the previous day in UTC). These are calendar dates, not instants.
function toISODate(d: Date): string {
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/** Monday-start weeks covering the full month, including leading/trailing days from
 * adjacent months so every week is complete (matches the prototype's grid). */
export function monthGridDays(year: number, month: number): Date[] {
  const first = new Date(year, month, 1);
  const startOffset = (first.getDay() + 6) % 7; // Mon=0 .. Sun=6
  const gridStart = new Date(year, month, 1 - startOffset);

  const last = new Date(year, month + 1, 0);
  const endOffset = 6 - ((last.getDay() + 6) % 7);
  const gridEnd = new Date(year, month + 1, endOffset);

  const days: Date[] = [];
  for (let d = new Date(gridStart); d <= gridEnd; d.setDate(d.getDate() + 1)) {
    days.push(new Date(d));
  }
  return days;
}

export function dateKey(d: Date): string {
  return toISODate(d);
}

/** The backend never stores "missed" - a Beat job doesn't flip it (confirmed against
 * backend/core/derived.py's compute_flags, which treats an overdue "planned" entry as the
 * signal rather than mutating state on a read). Replicate that same derivation here instead
 * of checking a literal status === "missed", which the API will never actually send. */
export function derivedStatus(entry: ScheduledWorkout, today: Date = new Date()): ScheduledWorkoutStatus {
  if (entry.status === "planned" && entry.date < toISODate(today)) {
    return "missed";
  }
  return entry.status;
}
