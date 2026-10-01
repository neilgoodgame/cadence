import { parsePace } from "../lib/format";
import { formatPaceBound } from "../lib/thresholdFields";
import type { ThresholdFieldName } from "../api/types";

export type ChartRange = "all" | "1y" | "16w";

const MS_PER_DAY = 86_400_000;

// threshold_pace's value is the display-ready "M:SS" string - every other field is already a
// plain number of watts.
export function numericValue(field: ThresholdFieldName, value: number | string): number | null {
  return field === "threshold_pace" ? parsePace(String(value)) : Number(value);
}

/** Pace is seconds/km - lower is better, the opposite of the two power fields. A point with no
 * previous value (the first real entry in the whole series) always counts as an improvement -
 * there's nothing for it to have dropped from. */
export function isImprovement(field: ThresholdFieldName, value: number, previousValue: number | null): boolean {
  if (previousValue == null) return true;
  return field === "threshold_pace" ? value < previousValue : value > previousValue;
}

/** Best means max for power fields, min seconds for pace (lower is faster). Null for an empty
 * series - the chart has nothing to draw a best-line against. */
export function bestValue(field: ThresholdFieldName, values: number[]): number | null {
  if (values.length === 0) return null;
  return field === "threshold_pace" ? Math.min(...values) : Math.max(...values);
}

/** Month x-axis ticks get sparser as the visible span grows, so labels never overlap: every
 * month up to ~13 months, every 3 up to ~26 months, every 6 beyond that. */
export function monthTickStep(spanDays: number): 1 | 3 | 6 {
  if (spanDays <= 400) return 1;
  if (spanDays <= 800) return 3;
  return 6;
}

export function rangeStartDate(range: ChartRange, today: Date): Date | null {
  if (range === "all") return null;
  const days = range === "1y" ? 365 : 112;
  return new Date(today.getTime() - days * MS_PER_DAY);
}

export interface ClippedPoint {
  date: Date;
  value: number;
  /** false for the synthetic point `clipToRange` inserts at the window start - it carries in
   * the value in effect at that moment, but isn't a real ledger entry (no change-point dot,
   * doesn't count toward `bestValue`). */
  real: boolean;
}

/** Clips a chronological series to the trailing `range` window. When the window start falls
 * inside a step (not exactly on a change point), a synthetic point is inserted at the window
 * start carrying in whatever value was in effect then, so the visible line doesn't start
 * mid-air at the first entry that happens to fall after the cutoff. Entries at or before the
 * cutoff are otherwise dropped entirely. */
export function clipToRange(points: ClippedPoint[], range: ChartRange, today: Date): ClippedPoint[] {
  const start = rangeStartDate(range, today);
  if (start == null) return points;
  const startMs = start.getTime();
  let before: ClippedPoint | undefined;
  const after: ClippedPoint[] = [];
  for (const p of points) {
    if (p.date.getTime() <= startMs) before = p;
    else after.push(p);
  }
  return before ? [{ date: start, value: before.value, real: false }, ...after] : after;
}

/** The tooltip's delta line - "First recorded value" for a series' first point, otherwise a
 * signed/arrowed comparison against the previous value. Deliberately its own format (space
 * before the unit, a true minus sign) rather than reusing the other threshold-delta helpers
 * elsewhere, which format slightly differently for their own contexts. */
export function tooltipDeltaText(field: ThresholdFieldName, value: number, previousValue: number | null): string {
  if (previousValue == null) return "First recorded value";
  if (field === "threshold_pace") {
    const delta = value - previousValue;
    const arrow = delta < 0 ? "▼" : "▲";
    return `${arrow} ${formatPaceBound(Math.abs(delta))} /km vs previous`;
  }
  const delta = Math.round(value - previousValue);
  const sign = delta > 0 ? "+" : "−";
  return `${sign}${Math.abs(delta)} W vs previous`;
}

export function tooltipSourceText(hasSource: boolean, improved: boolean): string {
  if (!hasSource) return "Entered manually";
  return improved ? "Set by a qualifying activity" : "Previous best aged out of window";
}

/** Whole days a value was (or has been) in effect, from `date` until `nextDate` (the next
 * change point's date, or today for the current value). */
export function tooltipDays(date: Date, nextDate: Date): number {
  return Math.round((nextDate.getTime() - date.getTime()) / MS_PER_DAY);
}
