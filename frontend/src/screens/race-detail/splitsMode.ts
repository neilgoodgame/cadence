import type { SplitsMode } from "../../api/types";

/** README §4.2.6 - "Laps if there are laps; otherwise 1 km for races of 10 km or less, else
 * 5 km." Pulled out as a pure function so SplitsCard's fallback-when-no-laps behavior (the
 * laps segment is disabled when `has_laps:false`) is independently testable. */
export function defaultSplitsMode(hasLaps: boolean, distanceKm: number | null): SplitsMode {
  if (hasLaps) return "laps";
  if (distanceKm != null && distanceKm <= 10) return "1";
  return "5";
}
