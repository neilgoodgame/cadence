/** Standard race distances this app recognizes for bucket-based training-block-length
 * defaults (an athlete's race_block_week_defaults preference, matched against a new A race's
 * own distance_km) - mirrors backend/core/race_distances.py and backend_java's
 * RaceDistanceBuckets. Also backs AddRaceModal/EditRaceModal's RUN_PRESETS distance buttons,
 * so the two lists can't drift apart. */

export interface RaceDistanceBucket {
  key: string;
  label: string;
  /** null for "ultra" - the open-ended catch-all for anything past marathon distance. */
  km: number | null;
}

export const RACE_DISTANCE_BUCKETS: RaceDistanceBucket[] = [
  { key: "5k", label: "5K", km: 5 },
  { key: "10k", label: "10K", km: 10 },
  { key: "10mi", label: "10 Mile", km: 16.0934 },
  { key: "half", label: "Half Marathon", km: 21.0975 },
  { key: "marathon", label: "Marathon", km: 42.195 },
  { key: "ultra", label: "Ultra", km: null },
];

const MARATHON_KM = RACE_DISTANCE_BUCKETS.find((b) => b.key === "marathon")!.km!;

/** Same ±2% tolerance as the backend's COMPARISON_DISTANCE_TOLERANCE/resolve_block_weeks. */
const DISTANCE_TOLERANCE = 0.02;

/** The nearest standard race distance this distance matches, for pre-filling a new A race's
 * block length from the athlete's race_block_week_defaults - null if there's no distance to
 * match (e.g. not a run, or distance not entered yet). */
export function matchDistanceBucket(distanceKm: number | null | undefined): string | null {
  if (!distanceKm) return null;
  for (const bucket of RACE_DISTANCE_BUCKETS) {
    if (bucket.km == null) {
      if (distanceKm > MARATHON_KM * (1 + DISTANCE_TOLERANCE)) return bucket.key;
    } else if (Math.abs(distanceKm - bucket.km) <= bucket.km * DISTANCE_TOLERANCE) {
      return bucket.key;
    }
  }
  return null;
}

export interface PhaseWeeks {
  base: number;
  build: number;
  peak: number;
  taper: number;
}

/** Same 37.5% Base / 37.5% Build / 12.5% Peak / remainder Taper split as backend/races/
 * blocks.py's phases() - lets the form show a default phase breakdown for a given block
 * length instantly, without a round trip, while the athlete is still typing. */
export function defaultPhaseWeeks(blockWeeks: number): PhaseWeeks {
  const base = Math.round(0.375 * blockWeeks);
  const build = Math.round(0.375 * blockWeeks);
  const peak = Math.max(1, Math.round(0.125 * blockWeeks));
  const taper = blockWeeks - base - build - peak;
  return { base, build, peak, taper };
}
