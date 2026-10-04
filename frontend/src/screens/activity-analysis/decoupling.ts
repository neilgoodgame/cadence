import type { Activity, DecouplingReason } from "../../api/types";

export type DecouplingBand = "good" | "moderate" | "high";

export const BAND_COLOR: Record<DecouplingBand, string> = {
  good: "#2fa66a",
  moderate: "#f0a02e",
  high: "#e0442e",
};

export function decouplingBand(pct: number): DecouplingBand {
  if (pct < 5) return "good";
  if (pct <= 10) return "moderate";
  return "high";
}

export function bandLabel(band: DecouplingBand): string {
  return band === "good" ? "Good" : band === "moderate" ? "Moderate" : "High";
}

/** left:% for the 4×16px marker on the 0-15% band bar. */
export function bandMarkerPct(pct: number): number {
  return Math.min(100, (pct / 15) * 100);
}

function viLimit(sport: Activity["sport"]): number {
  return sport === "run" ? 1.04 : 1.06;
}

/** One qualification-check chip, always shown (checks row never hides even on a not-scored
 * session) - see the design spec's Checks row table. */
export interface DecouplingCheck {
  label: string;
  pass: boolean;
  tooltip: string;
}

export function decouplingChecks(activity: Activity): DecouplingCheck[] {
  const reasons = activity.decoupling_reasons;
  const limit = viLimit(activity.sport);
  return [
    {
      label: `VI ${activity.decoupling_vi != null ? activity.decoupling_vi.toFixed(2) : "—"} ≤ ${limit}`,
      pass: !reasons.includes("variable"),
      tooltip: `Variability index (NP ÷ avg power). Bike limit 1.06, run 1.04.`,
    },
    {
      label: `IF ${activity.decoupling_if != null ? activity.decoupling_if.toFixed(2) : "—"} ≤ 0.85`,
      pass: !reasons.includes("intensity") && !reasons.includes("no_threshold"),
      tooltip: `Intensity factor (NP ÷ FTP). Above 0.85 drift is expected.`,
    },
    {
      label: `${activity.steady_seconds != null ? Math.round(activity.steady_seconds / 60) : "—"} min steady ≥ 60`,
      pass: !reasons.includes("short"),
      tooltip: `Moving time after the 10-min warm-up; stops ≥ 5 min removed.`,
    },
    {
      label: `HR ${activity.decoupling_hr_coverage_pct ?? "—"}% · power ${activity.decoupling_power_coverage_pct ?? "—"}%`,
      pass: !reasons.includes("hr_coverage") && !reasons.includes("power_coverage"),
      tooltip: `Coverage: HR ≥ 90%, power ≥ 95%.`,
    },
  ];
}

function reasonText(reason: DecouplingReason, activity: Activity): string {
  switch (reason) {
    case "variable":
      return `too variable (VI ${activity.decoupling_vi?.toFixed(2) ?? "—"}, limit ${viLimit(activity.sport)})`;
    case "intensity":
      return `too intense (IF ${activity.decoupling_if?.toFixed(2) ?? "—"}, limit 0.85)`;
    case "short":
      return `too short (${activity.steady_seconds != null ? Math.round(activity.steady_seconds / 60) : 0} min steady, needs 60)`;
    case "hr_coverage":
      return `HR coverage ${activity.decoupling_hr_coverage_pct ?? 0}% (needs 90%)`;
    case "power_coverage":
      return `power coverage ${activity.decoupling_power_coverage_pct ?? 0}% (needs 95%)`;
    case "no_threshold":
      return `no threshold on record for this activity's date`;
    default:
      return reason;
  }
}

/** "too variable (VI 1.12, limit 1.06)" - the first (highest-priority) failing reason, matching
 * the order the backend appends them (variable, then intensity/no_threshold, short, HR
 * coverage, power coverage). */
export function notScoredReasonText(activity: Activity): string {
  const first = activity.decoupling_reasons[0];
  return first ? reasonText(first, activity) : "not enough data";
}

function fmtPower(w: number): string {
  return `${Math.round(w)} W`;
}

/** "Efficiency fell from 1.53 to 1.45 W/bpm: heart rate rose 3 bpm while power eased 6 W." -
 * direction-aware per the actual deltas between halves. */
export function decouplingSummaryText(activity: Activity): string | null {
  if (activity.ef_first == null || activity.ef_second == null || activity.decoupling_halves.length !== 2) return null;
  const [h1, h2] = activity.decoupling_halves;
  const efDir = activity.ef_second >= activity.ef_first ? "rose" : "fell";
  const ef1 = activity.ef_first.toFixed(2);
  const ef2 = activity.ef_second.toFixed(2);
  if (h1.hr == null || h2.hr == null || h1.power == null || h2.power == null) {
    return `Efficiency ${efDir} from ${ef1} to ${ef2} W/bpm.`;
  }
  const hrDelta = Math.abs(h2.hr - h1.hr);
  const hrDir = h2.hr >= h1.hr ? "rose" : "fell";
  const powerDelta = Math.abs(h2.power - h1.power);
  const powerDir = h2.power <= h1.power ? "eased" : "rose";
  return `Efficiency ${efDir} from ${ef1} to ${ef2} W/bpm: heart rate ${hrDir} ${hrDelta} bpm while power ${powerDir} ${powerDelta} W.`;
}

export function fmtHalfTimeRange(startS: number | null, endS: number | null): string {
  if (startS == null || endS == null) return "—";
  const fmt = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  return `${fmt(startS)}–${fmt(endS)}`;
}

// ---- Durability strip (Activity Analysis Stats card) ----

export interface DurabilityTile {
  label: string;
  reached: boolean;
  /** "231 W" when reached, "Not reached" otherwise. */
  value: string;
  /** "5 min 268 W · 60 min —" (reached) or "Ride total 1,043 kJ — needs 1,000 kJ + the effort window" (not reached). */
  sub: string;
}

const BIKE_THRESHOLDS = [1000, 2000, 3000];
const RUN_THRESHOLDS_MIN = [60, 90];
const PRIMARY_WINDOW_S = 1200; // 20 min
const OTHER_WINDOWS: { label: string; windowS: number }[] = [
  { label: "5 min", windowS: 300 },
  { label: "60 min", windowS: 3600 },
];

export function durabilityTiles(activity: Activity): DurabilityTile[] {
  const isBike = activity.sport === "bike";
  const thresholds = isBike ? BIKE_THRESHOLDS : RUN_THRESHOLDS_MIN;
  const unit = isBike ? "kJ" : "min";
  const totalValue = isBike
    ? activity.avg_power != null
      ? Math.round((activity.avg_power * activity.moving_time) / 1000)
      : null
    : Math.round(activity.moving_time / 60);

  return thresholds.map((threshold) => {
    const label = `After ${threshold.toLocaleString()} ${unit}`;
    const primary = activity.durability.find((r) => r.threshold === threshold && r.window_s === PRIMARY_WINDOW_S);
    const anyRow = activity.durability.find((r) => r.threshold === threshold);
    if (!anyRow) {
      const totalLabel = totalValue != null ? `${totalValue.toLocaleString()} ${unit}` : `0 ${unit}`;
      const sportLabel = isBike ? "Ride" : "Run";
      return {
        label,
        reached: false,
        value: "Not reached",
        sub: `${sportLabel} total ${totalLabel} — needs ${threshold.toLocaleString()} ${unit} + the effort window`,
      };
    }
    const value = primary ? fmtPower(primary.power) : "—";
    const sub = OTHER_WINDOWS.map(({ label: windowLabel, windowS }) => {
      const row = activity.durability.find((r) => r.threshold === threshold && r.window_s === windowS);
      return `${windowLabel} ${row ? fmtPower(row.power) : "—"}`;
    }).join(" · ");
    return { label, reached: true, value, sub };
  });
}
