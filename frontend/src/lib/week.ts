import type { Activity } from "../api/types";

export function localIso(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

/** This week's training activities (non-walk, within the local 7-day grid window) - the single
 * source of truth for "this week" that WeekCalendar and WeekHrDistribution both filter by, so
 * splitting them into separately-positioned Dashboard sections can't let them drift apart. */
export function thisWeeksTrainingActivities(activities: Activity[]): Activity[] {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const start = new Date(today);
  start.setDate(start.getDate() - 6);
  const cutoff = localIso(start);
  return activities.filter((a) => a.sport !== "walk" && localIso(new Date(a.start_date)) >= cutoff);
}
