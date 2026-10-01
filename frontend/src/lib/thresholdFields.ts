import { formatPace } from "./format";
import { zoneRange } from "./zones";
import type { Zone, ZoneType } from "../api/types";

export type TabField = "ftp" | "critical_run_power" | "threshold_pace" | "lthr";

/** The four threshold tabs shared by the Dashboard's ThresholdSummaryCard and
 * ThresholdHistoryScreen - one definition so the two never drift apart. LTHR isn't part of the
 * rolling threshold-history ledger (ThresholdFieldName only covers the other three) - a plain
 * profile value, so it shows less wherever these fields are used: no delta/validity/stale/
 * history, just the value and its zones. */
export const FIELDS: { field: TabField; label: string; referenceLabel: string; unit: string; zoneType: ZoneType }[] = [
  { field: "ftp", label: "FTP", referenceLabel: "FTP", unit: "W", zoneType: "bike_power" },
  { field: "critical_run_power", label: "Critical running power", referenceLabel: "critical power", unit: "W", zoneType: "run_power" },
  { field: "threshold_pace", label: "Threshold pace", referenceLabel: "threshold pace", unit: "", zoneType: "pace" },
  { field: "lthr", label: "Heart rate", referenceLabel: "LTHR", unit: "bpm", zoneType: "heart_rate" },
];

export const UNIT_BY_ZONE_TYPE: Record<ZoneType, string> = {
  heart_rate: "bpm",
  bike_power: "W",
  run_power: "W",
  pace: "/km",
};

// A field's value is already "M:SS" for threshold_pace (matches the backend's value_pace field
// verbatim) - not seconds, so it's displayed as-is rather than run through formatPace (which
// expects a number of seconds, not a string).
export function formatValue(field: TabField, value: number | string): string {
  return field === "threshold_pace" ? `${value}/km` : String(value);
}

// zoneRange() returns raw seconds/km for pace - formatPace() always appends " /km", so this
// trims it for compact inline ranges ("4:36–5:12 /km" rather than "4:36 /km–5:12 /km").
export function formatPaceBound(seconds: number): string {
  return formatPace(seconds).replace(" /km", "");
}

export function formatZoneRange(zone: Zone, reference: number, zoneType: ZoneType): string {
  const range = zoneRange(zone, reference, zoneType);
  const unit = UNIT_BY_ZONE_TYPE[zoneType];
  if (zoneType === "pace") {
    return range.high != null
      ? `${formatPaceBound(range.low)}–${formatPaceBound(range.high)} ${unit}`
      : `> ${formatPaceBound(range.low)} ${unit}`;
  }
  return range.high != null ? `${range.low}–${range.high} ${unit}` : `${range.low}+ ${unit}`;
}
