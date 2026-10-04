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

/** Fixed 0-15 for decoupling (with Good/Moderate/High bands); data-driven +-0.05 padding for EF. */
export function yDomain(metric: DurabilityMetric, sessions: DurabilitySession[]): [number, number] {
  if (metric === "dec") return [0, 15];
  const values = sessions.map((s) => s.ef).filter((v): v is number => v != null);
  if (values.length === 0) return [1, 2];
  const [min, max] = extent(values) as [number, number];
  return [min - 0.05, max + 0.05];
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
