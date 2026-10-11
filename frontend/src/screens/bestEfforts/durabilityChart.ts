import { extent } from "d3-array";
import type { DurabilityRollingPoint, DurabilitySession } from "../../api/types";

export type DurabilityMetric = "dec" | "ef";
export type DurabilitySportFilter = "all" | "ride" | "run";

export const SPORT_COLOR: Record<"ride" | "run", string> = {
  ride: "#3d7fd6",
  run: "#ec4a26",
};

export const HOT_RING_COLOR = "#f0a02e";

/** The metric's own value for one session - decoupling_pct or whole-window ef. */
export function metricValue(session: DurabilitySession, metric: DurabilityMetric): number | null {
  return metric === "dec" ? session.decoupling_pct : session.ef;
}

export function metricAxisTitle(metric: DurabilityMetric): string {
  return metric === "dec" ? "Decoupling %" : "EF (W/bpm)";
}

export function metricTickLabel(metric: DurabilityMetric, value: number): string {
  return metric === "dec" ? `${Math.round(value)}%` : value.toFixed(2);
}

/** EF improves when it rises; decoupling improves when it falls - same "higher/lower is
 * better" asymmetry the rest of this app's threshold charts already encode per field. */
export function metricImproving(metric: DurabilityMetric, recent: number, previous: number): boolean {
  return metric === "dec" ? recent < previous : recent > previous;
}

/** Decoupling: at least 0-15 (the Good/Moderate/High bands), grown in steps of 5 to fit any
 * session or rolling-average value outside it. EF: data-driven +-0.05 padding. */
export function yDomain(metric: DurabilityMetric, sessions: DurabilitySession[], extra: number[] = []): [number, number] {
  if (metric === "dec") {
    const values = [...sessions.map((s) => s.decoupling_pct), ...extra].filter((v) => Number.isFinite(v));
    if (values.length === 0) return [0, 15];
    const [min, max] = extent(values) as [number, number];
    return [Math.min(0, Math.floor(min / 5) * 5), Math.max(15, Math.ceil(max / 5) * 5)];
  }
  const values = sessions.map((s) => s.ef).filter((v): v is number => v != null);
  if (values.length === 0) return [1, 2];
  const [min, max] = extent(values) as [number, number];
  return [min - 0.05, max + 0.05];
}

/** Gridline/tick values for the decoupling axis: every 5, or every 10 once the range gets tall. */
export function decouplingTicks([min, max]: [number, number]): number[] {
  const step = max - min > 40 ? 10 : 5;
  const ticks: number[] = [];
  for (let v = Math.ceil(min / step) * step; v <= max; v += step) ticks.push(v);
  return ticks;
}

export interface RollingSeries {
  sport: "ride" | "run";
  points: DurabilityRollingPoint[];
}

export function visibleRollingSeries(
  rolling: { ride?: DurabilityRollingPoint[]; run?: DurabilityRollingPoint[] },
  sportFilter: DurabilitySportFilter,
): RollingSeries[] {
  const series: RollingSeries[] = [];
  if ((sportFilter === "all" || sportFilter === "ride") && rolling.ride) series.push({ sport: "ride", points: rolling.ride });
  if ((sportFilter === "all" || sportFilter === "run") && rolling.run) series.push({ sport: "run", points: rolling.run });
  return series;
}
