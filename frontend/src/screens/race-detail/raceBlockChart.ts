import type { RaceBlockWeek } from "../../api/types";

export type BlockChartMetric = "tss" | "distance" | "time";

/** Which sport(s) the chart's bars count - "all" stacks run/ride/other segments in one bar. */
export type BlockChartSport = "all" | "run" | "ride";

/** A bar's stacked segment - "other" is whatever the week's total holds beyond runs and rides
 * (swims, strength, ...), so it only ever appears for the "all" filter on tss/time. */
export type BlockSegmentSport = "run" | "ride" | "other";

export const SEGMENT_COLOR: Record<BlockSegmentSport, string> = {
  run: "var(--sport-run)",
  ride: "var(--sport-ride)",
  other: "var(--ink3)",
};

export const SEGMENT_LABEL: Record<BlockSegmentSport, string> = { run: "Run", ride: "Ride", other: "Other" };

/** The metric's own value for one week - null when a planned week has nothing scheduled yet
 * (matches RaceBlockWeek's own "all null" convention for that case). */
export function metricValue(week: RaceBlockWeek, metric: BlockChartMetric, sport: BlockChartSport = "all"): number | null {
  switch (metric) {
    case "tss":
      return sport === "run" ? week.run_tss : sport === "ride" ? week.ride_tss : week.tss;
    case "distance":
      if (sport === "run") return week.run_km;
      if (sport === "ride") return week.ride_km;
      if (week.run_km == null && week.ride_km == null) return null;
      return (week.run_km ?? 0) + (week.ride_km ?? 0);
    case "time":
      if (sport === "run") return week.run_moving_s != null ? week.run_moving_s / 3600 : null;
      if (sport === "ride") return week.ride_moving_s != null ? week.ride_moving_s / 3600 : null;
      return week.hours;
  }
}

export interface BlockChartSegment {
  sport: BlockSegmentSport;
  /** Bottom and top of the segment, in metric units - segments stack run, ride, other. */
  from: number;
  to: number;
}

/** Splits a week's bar into stacked per-sport segments. For a single-sport filter that's one
 * segment; for "all" it's run, then ride, then any remainder of the total as "other". */
export function barSegments(week: RaceBlockWeek, metric: BlockChartMetric, sport: BlockChartSport): BlockChartSegment[] {
  const total = metricValue(week, metric, sport);
  if (total == null || total <= 0) return [];
  if (sport !== "all") return [{ sport, from: 0, to: total }];

  const segments: BlockChartSegment[] = [];
  let top = 0;
  for (const part of ["run", "ride"] as const) {
    const value = Math.min(metricValue(week, metric, part) ?? 0, total - top);
    if (value > 0) {
      segments.push({ sport: part, from: top, to: top + value });
      top += value;
    }
  }
  if (total - top > 1e-9) segments.push({ sport: "other", from: top, to: total });
  return segments;
}

export function metricAxisLabel(metric: BlockChartMetric): string {
  return metric === "tss" ? "TSS" : metric === "distance" ? "km" : "hours";
}

export interface BlockChartBar {
  week: RaceBlockWeek;
  index: number;
  /** Total bar height for the chosen metric - null for an unscheduled planned week. */
  value: number | null;
  segments: BlockChartSegment[];
  /** The hatched "race" portion of the bar - 0 outside the tss metric, and outside the "all"
   * filter (the API doesn't split a week's race TSS by sport). */
  raceValue: number;
  isPlanned: boolean;
  hasRace: boolean;
}

export interface BlockChartPoint {
  index: number;
  ctl: number;
  planned: boolean;
}

export interface BlockChartData {
  bars: BlockChartBar[];
  ctlLine: BlockChartPoint[];
  comparisonCtlLine: BlockChartPoint[];
  /** One ghost-bar value per week index, aligned to `bars` - null where there's no
   * comparison week or no value for the metric. */
  comparisonValues: (number | null)[];
  yMax: number;
  ctlMax: number;
}

/** Shapes a block's weekly series (plus an optional aligned comparison block) into bars and
 * CTL lines for RaceBlockChart - mirrors bestEfforts/durabilityChart.ts's split between pure
 * data-shaping and the SVG-rendering component. */
export function buildBlockChart(
  weeks: RaceBlockWeek[],
  comparisonWeeks: RaceBlockWeek[] | null,
  metric: BlockChartMetric,
  sport: BlockChartSport = "all",
): BlockChartData {
  const bars: BlockChartBar[] = weeks.map((week, index) => {
    const value = metricValue(week, metric, sport);
    const raceValue = metric === "tss" && sport === "all" && value != null ? Math.min(value, week.tss_races ?? 0) : 0;
    return {
      week,
      index,
      value,
      segments: barSegments(week, metric, sport),
      raceValue,
      isPlanned: week.planned,
      hasRace: week.races.length > 0,
    };
  });

  const values = bars.map((b) => b.value ?? 0);
  const comparisonValues = (comparisonWeeks ?? []).map((w) => metricValue(w, metric, sport));
  const yMax = Math.max(1, ...values, ...comparisonValues.filter((v): v is number => v != null));

  const ctlLine: BlockChartPoint[] = weeks.map((week, index) => ({ index, ctl: week.ctl ?? 0, planned: week.planned }));
  const comparisonCtlLine: BlockChartPoint[] = (comparisonWeeks ?? []).map((week, index) => ({
    index,
    ctl: week.ctl ?? 0,
    planned: week.planned,
  }));
  const ctlMax = Math.max(1, ...ctlLine.map((p) => p.ctl), ...comparisonCtlLine.map((p) => p.ctl));

  return { bars, ctlLine, comparisonCtlLine, comparisonValues, yMax, ctlMax };
}

/** Splits a CTL line into its actual (solid) and projected (dashed) portions - the dashed
 * segment repeats the last actual point first, so the two lines join up visually with no gap. */
export function splitProjectedLine(points: BlockChartPoint[]): { actual: BlockChartPoint[]; projected: BlockChartPoint[] } {
  const firstPlannedIndex = points.findIndex((p) => p.planned);
  if (firstPlannedIndex === -1) return { actual: points, projected: [] };
  if (firstPlannedIndex === 0) return { actual: [], projected: points };
  return { actual: points.slice(0, firstPlannedIndex), projected: points.slice(firstPlannedIndex - 1) };
}

/** Index of the week containing `today` (the week whose [weekStart, weekStart+6] range
 * contains it), or -1 if today falls outside the block entirely. */
export function currentWeekIndex(weeks: RaceBlockWeek[], today: Date): number {
  const todayMs = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  for (let i = 0; i < weeks.length; i++) {
    const start = new Date(weeks[i].week_start + "T00:00:00Z").getTime();
    const end = start + 6 * 86_400_000;
    if (todayMs >= start && todayMs <= end) return i;
  }
  return -1;
}
