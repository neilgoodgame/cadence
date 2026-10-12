/** Pure pacing/fuelling plan math for an upcoming race with a goal - see
 * design_handoff_races/README.md §3.2/§4.2.5. Computed client-side; the only backend input is
 * `sweatRateLPerH` (RaceBlock.sweat_rate_l_per_h). */

export type SplitStrategy = "even" | "negative";

export interface RacePlanInput {
  distanceKm: number;
  goalTimeS: number;
  strategy: SplitStrategy;
  /** 45 | 60 | 75 | 90 g/h. */
  carbsGPerH: number;
  sweatRateLPerH: number;
}

export interface RacePlanHalves {
  firstS: number;
  secondS: number;
}

export interface GelEvent {
  atKm: number;
  atS: number;
}

export interface RacePlanFuel {
  gramsPerGel: number;
  intervalMin: number;
  gels: GelEvent[];
}

export interface RaceSplitFuelChip {
  type: "gel" | "drink";
  label: string;
}

export interface RaceSplitRow {
  splitKm: number;
  targetS: number;
  paceSPerKm: number;
  clockS: number;
  isFinish: boolean;
  chips: RaceSplitFuelChip[];
}

export interface RacePlan {
  halves: RacePlanHalves;
  fuel: RacePlanFuel;
  fluidLPerH: number;
  sodiumMgPerH: number;
  table: RaceSplitRow[];
}

const GEL_GRAMS = 25;
const NEGATIVE_SPLIT_FIRST_HALF_PENALTY = 0.01; // first half 1% slower than goal pace
const GEL_CUTOFF_BEFORE_FINISH_S = 10 * 60;
const SODIUM_MG_PER_LITRE = 700;
const MAX_FLUID_L_PER_H = 0.8;
const FLUID_SWEAT_RATE_FACTOR = 0.7;
const SPLIT_INTERVAL_KM = 5;

function computeHalves(input: RacePlanInput): RacePlanHalves {
  if (input.strategy === "even") {
    return { firstS: input.goalTimeS / 2, secondS: input.goalTimeS / 2 };
  }
  const goalPace = input.goalTimeS / input.distanceKm;
  const firstHalfPace = goalPace * (1 + NEGATIVE_SPLIT_FIRST_HALF_PENALTY);
  const firstS = firstHalfPace * (input.distanceKm / 2);
  const secondS = input.goalTimeS - firstS;
  return { firstS, secondS };
}

/** Elapsed time (s) at a given distance (km), under the chosen split strategy - piecewise
 * linear (one pace for each half under "negative", constant under "even"). */
function timeAtDistance(input: RacePlanInput, halves: RacePlanHalves, km: number): number {
  const halfKm = input.distanceKm / 2;
  if (input.strategy === "even" || km <= halfKm) {
    const pace = (input.strategy === "even" ? input.goalTimeS : halves.firstS * 2) / input.distanceKm;
    return pace * km;
  }
  const secondHalfPace = halves.secondS / halfKm;
  return halves.firstS + secondHalfPace * (km - halfKm);
}

/** Inverse of timeAtDistance - distance (km) reached at a given elapsed time (s). */
function distanceAtTime(input: RacePlanInput, halves: RacePlanHalves, atS: number): number {
  const halfKm = input.distanceKm / 2;
  const firstHalfPace = halves.firstS / halfKm;
  if (input.strategy === "even" || atS <= halves.firstS) {
    return atS / firstHalfPace;
  }
  const secondHalfPace = halves.secondS / halfKm;
  return halfKm + (atS - halves.firstS) / secondHalfPace;
}

function computeGels(input: RacePlanInput, halves: RacePlanHalves): RacePlanFuel {
  const intervalMin = (GEL_GRAMS / input.carbsGPerH) * 60;
  const intervalS = intervalMin * 60;
  const cutoffS = input.goalTimeS - GEL_CUTOFF_BEFORE_FINISH_S;
  const gels: GelEvent[] = [];
  for (let atS = intervalS; atS <= cutoffS; atS += intervalS) {
    gels.push({ atS, atKm: distanceAtTime(input, halves, atS) });
  }
  return { gramsPerGel: GEL_GRAMS, intervalMin, gels };
}

function computeFluidLPerH(sweatRateLPerH: number): number {
  return Math.min(MAX_FLUID_L_PER_H, FLUID_SWEAT_RATE_FACTOR * sweatRateLPerH);
}

function buildTable(input: RacePlanInput, halves: RacePlanHalves, gels: GelEvent[], fluidLPerH: number): RaceSplitRow[] {
  const rows: RaceSplitRow[] = [];
  let prevKm = 0;
  let prevS = 0;
  for (let splitKm = SPLIT_INTERVAL_KM; ; splitKm += SPLIT_INTERVAL_KM) {
    const isFinish = splitKm >= input.distanceKm;
    const endKm = isFinish ? input.distanceKm : splitKm;
    const clockS = isFinish ? input.goalTimeS : timeAtDistance(input, halves, endKm);
    const segmentS = clockS - prevS;
    const segmentKm = endKm - prevKm;
    const paceSPerKm = segmentKm > 0 ? segmentS / segmentKm : 0;

    const chips: RaceSplitFuelChip[] = [];
    for (const gel of gels) {
      if (gel.atKm > prevKm && gel.atKm <= endKm) {
        chips.push({ type: "gel", label: `Gel @ ${gel.atKm.toFixed(1)} km` });
      }
    }
    // Aid stations every 5km - this table is already on a 5km grid, so one per row.
    const drinkMl = Math.round(fluidLPerH * (segmentS / 3600) * 1000);
    if (drinkMl > 0) {
      chips.push({ type: "drink", label: `Drink ~${drinkMl} ml @ ${endKm.toFixed(0)} km` });
    }

    rows.push({ splitKm: Math.round(endKm * 10) / 10, targetS: segmentS, paceSPerKm, clockS, isFinish, chips });

    prevKm = endKm;
    prevS = clockS;
    if (isFinish) break;
  }
  return rows;
}

export function computeRacePlan(input: RacePlanInput): RacePlan {
  const halves = computeHalves(input);
  const fuel = computeGels(input, halves);
  const fluidLPerH = computeFluidLPerH(input.sweatRateLPerH);
  const sodiumMgPerH = fluidLPerH * SODIUM_MG_PER_LITRE;
  const table = buildTable(input, halves, fuel.gels, fluidLPerH);
  return { halves, fuel, fluidLPerH, sodiumMgPerH, table };
}
