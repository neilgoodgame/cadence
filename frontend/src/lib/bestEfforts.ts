import type { BestEffortKind, RecentTopEffort } from "../api/types";
import { formatDuration } from "./format";

/** Which family a best-effort kind belongs to, for grouping/labelling - shared by the Best
 * Efforts screen's own tabs and the Dashboard's "Top efforts this week" card. Distance/
 * Elevation aren't in this map at all yet: "Longest rides"/"Biggest climbs" on
 * BestEffortsScreen.tsx are computed client-side from listActivities, not periodized, so they
 * can't produce reliable 16w/1y/all ranks - deferred, not wired into any BestEffortKind. */
export const BEST_EFFORT_FAMILY: Record<BestEffortKind, string> = {
  cycling_power: "Power",
  running_power: "Power",
  running_pace: "Pace",
  cycling_hr: "Heart rate",
  running_hr: "Heart rate",
};

/** Every family, in the fixed display order the Dashboard card and Best Efforts screen both
 * use. Distance/Elevation are included so a later data addition for those only needs a
 * BEST_EFFORT_FAMILY entry, not a UI change. */
export const BEST_EFFORT_FAMILY_ORDER = ["Power", "Pace", "Heart rate", "Distance", "Elevation"];

const WINDOW_LABELS: Record<string, string> = {
  "5s": "5 s",
  "15s": "15 s",
  "30s": "30 s",
  "1min": "1 min",
  "5min": "5 min",
  "10min": "10 min",
  "20min": "20 min",
  "60min": "60 min",
  "1km": "1 km",
  "5km": "5 km",
  "10km": "10 km",
  "10mile": "10 mile",
  half_marathon: "Half marathon",
  "30km": "30 km",
  marathon: "Marathon",
  "50km": "50 km",
};

/** Wire window value (e.g. "5min", "half_marathon") -> display label (e.g. "5 min", "Half
 * marathon"). Falls back to the raw wire value for anything unmapped, rather than throwing -
 * the window tables are a fixed, known set, but a display helper shouldn't be the thing that
 * breaks if they ever grow. */
export function windowLabel(window: string): string {
  return WINDOW_LABELS[window] ?? window;
}

/** Window (duration form, e.g. "5min", "1min") -> seconds, or 0 if not a duration window. */
export function windowToSeconds(window: string): number {
  const m = window.match(/^(\d+)\s*(s|sec|m|min|h|hr)/i);
  if (!m) return 0;
  const n = parseInt(m[1]);
  const unit = m[2].toLowerCase();
  if (unit.startsWith("h")) return n * 3600;
  if (unit.startsWith("m")) return n * 60;
  return n;
}

/** Window (distance form, e.g. "10km", "half_marathon") -> km, or 0 if not a distance window. */
export function windowToKm(window: string): number {
  if (/marathon/i.test(window)) return /half/i.test(window) ? 21.097 : 42.195;
  const mile = window.match(/^([\d.]+)\s*mile/i);
  if (mile) return parseFloat(mile[1]) * 1.609344;
  const m = window.match(/^([\d.]+)\s*km/i);
  return m ? parseFloat(m[1]) : 0;
}

/** "5 min power" / "Half marathon" - matches the Dashboard design's effortLabel(e): the metric
 * alone for Distance/Elevation (their metric is already descriptive, e.g. "Longest run"), the
 * metric plus the lowercased family for everything else (Power/Pace/Heart rate), since those
 * metrics are bare window labels ("5 min") that need the family to read as a sentence. */
export function effortLabel(family: string, metricLabel: string): string {
  return family === "Distance" || family === "Elevation" ? metricLabel : `${metricLabel} ${family.toLowerCase()}`;
}

/** The best-effort's own value, formatted per its kind - pace as elapsed time for the window's
 * distance (e.g. "49:52"), power/HR as a rounded unit ("342 W" / "176 bpm"). */
export function formatBestEffortValue(kind: BestEffortKind, window: string, value: number): string {
  if (kind === "running_pace") {
    const km = windowToKm(window);
    return km > 0 ? formatDuration(value * km) : "—";
  }
  if (kind === "cycling_power" || kind === "running_power") {
    return `${Math.round(value)} W`;
  }
  return `${Math.round(value)} bpm`;
}

// ─── "Top efforts this week" (Dashboard card + WeekCalendar badges) ────────────────────────
//
// Shared by TopEffortsCard.tsx and WeekCalendar.tsx so both surfaces agree on exactly which
// activities/efforts qualify and how they're ranked - see the design handoff's rules:
// qualifies if recent (checked by the caller, which already only fetches the last N days) and
// top-3 in some period; headline = widest period with rank <= 3, checked all -> 1y -> 16w (so
// all-time #3 beats 16-week #1); sort by headline width desc, then rank asc, then date desc.

export const RECENT_TOP_EFFORTS_CAP = 3;

/** Rank fill colours - not app theme tokens (medals aren't a themed concept elsewhere), so
 * these are the literal design-spec hex values, usable directly or through hexToRgba below. */
export const MEDAL_COLORS: Record<1 | 2 | 3, string> = { 1: "#c9981a", 2: "#8e98a3", 3: "#c98a52" };
/** Text colour on a medal fill - white fails 4.5:1 contrast against all three medal colours. */
export const MEDAL_INK = "#1b1a18";

export function hexToRgba(hex: string, alpha: number): string {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

interface RecentTopEffortPeriodDef {
  key: "all" | "1y" | "16w";
  label: string;
  adj: string;
  code: string;
}

/** Widest-first - headlineOf relies on this order to prefer "all" over "1y" over "16w". */
export const RECENT_TOP_EFFORT_PERIODS: RecentTopEffortPeriodDef[] = [
  { key: "all", label: "All time", adj: "All-time", code: "AT" },
  { key: "1y", label: "1 yr", adj: "1-year", code: "1Y" },
  { key: "16w", label: "16 wk", adj: "16-week", code: "16W" },
];

export interface RecentTopEffortHeadline extends RecentTopEffortPeriodDef {
  /** Position of `key` within RECENT_TOP_EFFORT_PERIODS, counted from the *narrow* end - "all"
   * has the highest width, so a wider qualifying period always outranks a narrower one
   * regardless of the two entries' actual numeric ranks. */
  width: number;
  rank: number;
}

/** The widest period this entry ranks <= 3 in, or null if it doesn't qualify in any tracked
 * period at all. */
export function recentTopEffortHeadline(entry: RecentTopEffort): RecentTopEffortHeadline | null {
  for (let i = 0; i < RECENT_TOP_EFFORT_PERIODS.length; i++) {
    const def = RECENT_TOP_EFFORT_PERIODS[i];
    const rank = entry.ranks[def.key];
    if (rank != null && rank <= 3) {
      return { ...def, width: RECENT_TOP_EFFORT_PERIODS.length - i, rank };
    }
  }
  return null;
}

export type QualifyingRecentEffort = RecentTopEffort & { hl: RecentTopEffortHeadline };

/** Every entry that qualifies (has a headline at all), sorted headline-width desc, then rank
 * asc, then date desc - the exact order both the card's activity grouping and a day's "best
 * effort for this activity" badge-colour pick rely on. */
export function qualifyingRecentEfforts(entries: RecentTopEffort[]): QualifyingRecentEffort[] {
  const withHeadline: QualifyingRecentEffort[] = [];
  for (const entry of entries) {
    const hl = recentTopEffortHeadline(entry);
    if (hl) withHeadline.push({ ...entry, hl });
  }
  return withHeadline.sort((x, y) => y.hl.width - x.hl.width || x.hl.rank - y.hl.rank || y.date.localeCompare(x.date));
}

export interface RecentTopEffortGroup {
  activityId: string;
  items: QualifyingRecentEffort[];
}

/** Groups an already-sorted qualifying list by activity, preserving first-seen order - since
 * the input is sorted by headline strength, each group's own position in the result is exactly
 * its best effort's position, with no extra re-sort needed. */
export function groupRecentEffortsByActivity(list: QualifyingRecentEffort[]): RecentTopEffortGroup[] {
  const groups: RecentTopEffortGroup[] = [];
  const byId = new Map<string, RecentTopEffortGroup>();
  for (const item of list) {
    let group = byId.get(item.activity_id);
    if (!group) {
      group = { activityId: item.activity_id, items: [] };
      byId.set(item.activity_id, group);
      groups.push(group);
    }
    group.items.push(item);
  }
  return groups;
}
