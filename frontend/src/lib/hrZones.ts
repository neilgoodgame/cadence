import type { Zone } from "../api/types";

/**
 * Heart-rate zone generation from physiological parameters - a port of
 * fit-analyser's generate_hr_zones.py. Three methods:
 *
 *   max_hr    Percentage of maximum heart rate (Fox 1971 / Garmin default).
 *   karvonen  Percentage of heart rate reserve (Karvonen method).
 *   coggan    Anchored to lactate threshold heart rate (Coggan method).
 */

export type HrZoneMethod = "max_hr" | "karvonen" | "coggan";

export interface HrZoneParams {
  /** Only used by the karvonen method. */
  restingHr: number | null;
  lthr: number;
  maxHr: number;
}

export interface HrZoneBand {
  name: string;
  label: string;
  description: string;
  minBpm: number;
  maxBpm: number;
}

export const HR_ZONE_METHODS: Record<HrZoneMethod, { label: string; blurb: string }> = {
  max_hr: { label: "% of max HR", blurb: "Zones as a percentage of maximum heart rate (Fox 1971 / Garmin default)." },
  karvonen: { label: "Karvonen", blurb: "Zones as a percentage of heart rate reserve (max HR − resting HR)." },
  coggan: { label: "Coggan", blurb: "Zones anchored to lactate threshold heart rate (68/83/94/105%)." },
};

const ZONE_DEFS: { name: string; label: string; description: string }[] = [
  {
    name: "Z1",
    label: "Active Recovery",
    description: "Very light effort, warm-up and cool-down. Fully aerobic, no fatigue accumulation.",
  },
  {
    name: "Z2",
    label: "Endurance",
    description: "Easy aerobic base work. Conversational pace, the bulk of long run and easy ride volume.",
  },
  {
    name: "Z3",
    label: "Tempo",
    description: "Comfortably hard. Marathon to half-marathon effort. Sustained aerobic work.",
  },
  {
    name: "Z4",
    label: "Threshold",
    description: "Lactate threshold effort. 10km to half-marathon race pace. Sustainable for 20-60 minutes.",
  },
  {
    name: "Z5",
    label: "VO2max",
    description: "Very hard. 5km race pace and above. Sustainable only for short intervals of 3-8 minutes.",
  },
];

/** Shared by max_hr (% of max) and karvonen (% of reserve). */
const PCT_BANDS: [number, number][] = [
  [0.5, 0.6],
  [0.6, 0.7],
  [0.7, 0.8],
  [0.8, 0.9],
  [0.9, 1.0],
];

/** Coggan boundaries as % of LTHR; the last band is open-ended (capped at max HR). */
const COGGAN_BANDS: [number, number][] = [
  [0, 0.68],
  [0.68, 0.83],
  [0.84, 0.94],
  [0.95, 1.05],
  [1.06, Infinity],
];

/** Returns a validation error message, or null when the params are usable for the method. */
export function validateHrZoneParams(method: HrZoneMethod, { restingHr, lthr, maxHr }: HrZoneParams): string | null {
  if (!Number.isFinite(lthr) || lthr <= 0) return "Enter your LTHR.";
  if (!Number.isFinite(maxHr) || maxHr <= 0) return "Enter your max HR.";
  if (method === "karvonen" && (restingHr == null || restingHr <= 0)) {
    return "The Karvonen method needs your resting HR.";
  }
  if (restingHr != null && restingHr > 0 && restingHr >= lthr) return "Resting HR must be less than LTHR.";
  if (lthr >= maxHr) return "LTHR must be less than max HR.";
  return null;
}

export function generateHrZones(method: HrZoneMethod, params: HrZoneParams): HrZoneBand[] {
  const { restingHr, lthr, maxHr } = params;
  const last = ZONE_DEFS.length - 1;

  return ZONE_DEFS.map((def, i) => {
    let minBpm: number;
    let maxBpm: number;

    if (method === "max_hr") {
      const [loPct, hiPct] = PCT_BANDS[i];
      minBpm = Math.round(maxHr * loPct);
      maxBpm = i < last ? Math.round(maxHr * hiPct) - 1 : maxHr;
    }
    else if (method === "karvonen") {
      const hrr = maxHr - (restingHr ?? 0);
      const [loPct, hiPct] = PCT_BANDS[i];
      minBpm = Math.round((restingHr ?? 0) + hrr * loPct);
      maxBpm = i < last ? Math.round((restingHr ?? 0) + hrr * hiPct) - 1 : maxHr;
    }
    else {
      const [, hiPct] = COGGAN_BANDS[i];
      // Bands are % of LTHR with rounding, so each zone starts 1 bpm above the previous
      // zone's ceiling rather than re-deriving (the 0.83 → 0.84 gap would otherwise skip a bpm).
      minBpm = i === 0 ? 0 : cogganCeiling(i - 1, lthr, maxHr) + 1;
      maxBpm = Number.isFinite(hiPct) ? Math.min(Math.round(lthr * hiPct), maxHr) : maxHr;
      if (i < last) maxBpm -= 1;
    }

    return { ...def, minBpm, maxBpm };
  });
}

function cogganCeiling(i: number, lthr: number, maxHr: number): number {
  const hiPct = COGGAN_BANDS[i][1];
  const capped = Math.min(Math.round(lthr * hiPct), maxHr);
  return i < ZONE_DEFS.length - 1 ? capped - 1 : maxHr;
}

/** Converts absolute bpm bands into the API's %-of-LTHR zone model. */
export function hrZoneBandsToZones(bands: HrZoneBand[], lthr: number): Zone[] {
  return bands.map((band) => ({
    name: `${band.name} ${band.label}`,
    low_pct: Math.round((band.minBpm / lthr) * 100),
    high_pct: Math.round((band.maxBpm / lthr) * 100),
  }));
}
