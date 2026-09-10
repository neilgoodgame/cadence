import type { Athlete, Lap } from "../../api/types";

/** Lightweight, reference-free target string for a lap's step (no FTP/max-HR/threshold-pace
 * available here to convert a % target into an absolute value - see workoutTree's targetInfo
 * for that fuller version, used in the workout builder where those references are on hand). */
export function formatStepTarget(lap: Lap): string | null {
  if (!lap.step_target_type) return null;
  const lo = lap.step_target_low ?? 0;
  const hi = lap.step_target_high ?? lo;
  const range = (unit: string) => (lo === hi ? `${lo}${unit}` : `${lo}–${hi}${unit}`);
  switch (lap.step_target_type) {
    case "power":
      return lap.step_power_unit === "watts" ? range("W") : range("% FTP");
    case "hr":
      return range("% max HR");
    case "pace":
      return range("% threshold pace");
    case "cadence":
      return range(" rpm");
    default:
      return "Open";
  }
}

/** The step's own target expressed as a %-of-reference intensity, for zoneColor - same
 * direction/scale as workoutTree's targetInfo (higher % = harder), just resolved against the
 * real athlete reference on hand here instead of a placeholder. Null when there's no target, or
 * a watts target with no known FTP to convert it against. */
export function stepZonePct(lap: Lap, athlete: Athlete): number | null {
  if (!lap.step_target_type || lap.step_target_low == null) return null;
  const lo = lap.step_target_low;
  const hi = lap.step_target_high ?? lo;
  const mid = (lo + hi) / 2;
  if (lap.step_target_type === "power") {
    if (lap.step_power_unit === "watts") return athlete.ftp ? (mid / athlete.ftp) * 100 : null;
    return mid;
  }
  // hr and pace targets are already stored as a %-of-reference (max HR / threshold pace), same
  // direction as power's %FTP - higher is harder in both cases.
  if (lap.step_target_type === "hr" || lap.step_target_type === "pace") return mid;
  return null;
}

/** How close the lap's actual avg power/HR came to its step's target, as a % of target (100 =
 * spot on). Power/HR only - pace compliance would need to parse athlete.threshold_pace's
 * "M:SS" string, which isn't worth the risk of a subtly-wrong conversion for a nice-to-have
 * badge; pace laps still get a zone-colored target, just no compliance number. */
export function compliancePct(lap: Lap, athlete: Athlete): number | null {
  if (!lap.step_target_type || lap.step_target_low == null) return null;
  const lo = lap.step_target_low;
  const hi = lap.step_target_high ?? lo;
  const mid = (lo + hi) / 2;
  if (lap.step_target_type === "power" && lap.avg_power != null) {
    const targetWatts = lap.step_power_unit === "watts" ? mid : athlete.ftp ? (mid / 100) * athlete.ftp : null;
    if (!targetWatts) return null;
    return Math.round((lap.avg_power / targetWatts) * 100);
  }
  if (lap.step_target_type === "hr" && lap.avg_hr != null && athlete.max_hr) {
    const targetBpm = (mid / 100) * athlete.max_hr;
    if (!targetBpm) return null;
    return Math.round((lap.avg_hr / targetBpm) * 100);
  }
  return null;
}

/** Green near target, amber a bit off, red well off - centered on 100% (unlike zoneColor, which
 * scales absolute intensity, not deviation from a target). */
export function complianceColor(pct: number): string {
  const deviation = Math.abs(pct - 100);
  if (deviation <= 5) return "#2fa66a";
  if (deviation <= 15) return "#f0a02e";
  return "#e0442e";
}

export interface SingleGroup {
  type: "single";
  lap: Lap;
}

export interface RepeatGroup {
  type: "repeat";
  key: string;
  reps: Lap[][];
}

export type LapGroup = SingleGroup | RepeatGroup;

/** Collapses consecutive laps that form a repeating cycle (e.g. 5x[block, rec], all pointing at
 * the same two WorkoutStep rows per position) into one group, so a 10-row interval set reads as
 * one line by default. Falls back to individual rows for anything that doesn't actually repeat
 * (repeat_index === 1 but no matching rep 2 follows) rather than guessing at a grouping. */
export function groupLaps(laps: Lap[]): LapGroup[] {
  const groups: LapGroup[] = [];
  let i = 0;
  while (i < laps.length) {
    const lap = laps[i];
    if (lap.repeat_index === 1) {
      let j = i;
      const rep1: Lap[] = [];
      while (j < laps.length && laps[j].repeat_index === 1) {
        rep1.push(laps[j]);
        j++;
      }
      const cycleLen = rep1.length;
      const reps: Lap[][] = [rep1];
      let k = j;
      let expected = 2;
      while (k + cycleLen <= laps.length) {
        const slice = laps.slice(k, k + cycleLen);
        const matches = slice.every((l, idx) => l.repeat_index === expected && l.workout_step_id === rep1[idx].workout_step_id);
        if (!matches) break;
        reps.push(slice);
        k += cycleLen;
        expected++;
      }
      if (reps.length > 1) {
        groups.push({ type: "repeat", key: `repeat-${lap.index}`, reps });
        i = k;
        continue;
      }
      for (const l of rep1) groups.push({ type: "single", lap: l });
      i = j;
      continue;
    }
    groups.push({ type: "single", lap });
    i++;
  }
  return groups;
}

export function sum(values: number[]): number {
  return values.reduce((a, b) => a + b, 0);
}

export function weightedAvg(laps: Lap[], value: (l: Lap) => number | null): number | null {
  let weightedSum = 0;
  let weight = 0;
  for (const l of laps) {
    const v = value(l);
    if (v == null) continue;
    weightedSum += v * l.duration;
    weight += l.duration;
  }
  return weight > 0 ? Math.round(weightedSum / weight) : null;
}
