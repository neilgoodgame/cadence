/** Calendar days from today to an ISO "YYYY-MM-DD" date - negative if it's in the past. Compares
 * local midnights, not instants, so "today" is always 0 regardless of time of day. */
export function daysUntil(dateStr: string): number {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((new Date(dateStr + "T00:00:00").getTime() - today.getTime()) / 86_400_000);
}
