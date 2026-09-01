import type { ThresholdFieldName, ThresholdHistoryPoint } from "../api/types";
import { formatPace, parsePace } from "../lib/format";

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

// Pace is seconds/km - a *lower* value is the improvement, the opposite of the two power
// fields. Mirrors dashboard/ThresholdSummaryCard.tsx's own deltaLabel (that one compares a
// field's current value against its previous value; this compares two adjacent ledger rows).
export function deltaLabel(field: ThresholdFieldName, value: number | string, previousValue: number | string): string | null {
  if (field === "threshold_pace") {
    const current = parsePace(String(value));
    const previous = parsePace(String(previousValue));
    if (current == null || previous == null || current === previous) return null;
    return `${current < previous ? "▼" : "▲"} ${formatPace(Math.abs(current - previous))} vs previous`;
  }
  const delta = Number(value) - Number(previousValue);
  if (delta === 0) return null;
  return `${delta > 0 ? "+" : ""}${delta}W vs previous`;
}
