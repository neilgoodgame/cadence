import type { ThresholdHistoryPoint } from "../api/types";

const MS_PER_DAY = 86_400_000;

// A "YYYY-MM-DD" string parses as UTC midnight - matching that here (rather than the local day
// boundary from `new Date()`) keeps every diff an exact whole number of days regardless of the
// viewer's own timezone.
function utcMidnightToday(): Date {
  const now = new Date();
  return new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()));
}

/** How many days each entry was (or has been) the recorded current value, same order as
 * `entries` (most-recent-first, per the API). Each entry's span runs from its own current_from
 * up to the next-more-recent entry's current_from - or today, for entry 0, since it's still
 * ongoing. */
export function daysInEffect(entries: ThresholdHistoryPoint[]): number[] {
  return entries.map((entry, i) => {
    const start = new Date(entry.current_from);
    const end = i === 0 ? utcMidnightToday() : new Date(entries[i - 1].current_from);
    return Math.max(0, Math.round((end.getTime() - start.getTime()) / MS_PER_DAY));
  });
}
