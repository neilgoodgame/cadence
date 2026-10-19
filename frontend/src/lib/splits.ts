import type { SectionType, SplitsMode } from "../api/types";
import { SECTION_LABELS } from "./courseSections";

/** README §4.2.6 - "Laps if there are laps; otherwise 1 km for races of 10 km or less, else
 * 5 km." Pulled out as a pure function so SplitsCard's fallback-when-no-laps behavior (the
 * laps segment is disabled when `has_laps:false`) is independently testable. Reused by
 * ActivitySplitsCard too - the rule has nothing race-specific about it. */
export function defaultSplitsMode(hasLaps: boolean, distanceKm: number | null): SplitsMode {
  if (hasLaps) return "laps";
  if (distanceKm != null && distanceKm <= 10) return "1";
  return "5";
}

/** The subset of RaceSplit/ActivitySplit's fields a row's label is derived from - both satisfy
 * this structurally, so SplitsCard and ActivitySplitsCard can share one implementation. */
export interface SplitLabelFields {
  label: string | null;
  section_type: SectionType | null;
  start_km: number;
  end_km: number;
}

export function splitRowLabel(split: SplitLabelFields, index: number, mode: SplitsMode): string {
  if (mode === "elevation" && split.section_type) {
    return `${SECTION_LABELS[split.section_type]} · ${split.start_km.toFixed(1)}–${split.end_km.toFixed(1)}`;
  }
  if (split.label) return split.label;
  if (mode === "laps") return `Lap ${index + 1} · ${split.start_km.toFixed(1)}–${split.end_km.toFixed(1)}`;
  return `${split.end_km.toFixed(mode === "1" ? 0 : 0)} km`;
}
