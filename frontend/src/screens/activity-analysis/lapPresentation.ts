import type { Athlete, Lap } from "../../api/types";
import { kindLabel, zoneColor } from "../workouts/workoutTree";

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
 * a watts target with no known power reference to convert it against.
 *
 * `powerReference` is the FTP/critical-run-power to convert a watts target against - callers
 * must pass the value effective as of the *activity's own date* (via GET
 * /v1/athletes/{id}/zones?activity_id=..., see LapsTab), not the athlete's current profile: an
 * activity recorded before the athlete's most recent threshold change would otherwise have every
 * lap compared against a reference that wasn't even true yet when it happened. HR keeps using
 * the athlete's live max_hr - heart-rate zones intentionally have no per-activity history (see
 * backend/athletes/zones.py::reference_for). */
export function stepZonePct(lap: Lap, powerReference: number | null): number | null {
  if (!lap.step_target_type || lap.step_target_low == null) return null;
  const lo = lap.step_target_low;
  const hi = lap.step_target_high ?? lo;
  const mid = (lo + hi) / 2;
  if (lap.step_target_type === "power") {
    if (lap.step_power_unit === "watts") return powerReference ? (mid / powerReference) * 100 : null;
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
 * badge; pace laps still get a zone-colored target, just no compliance number. See
 * stepZonePct's docstring for why `powerReference` must be activity-date-scoped, not live. */
export function compliancePct(lap: Lap, athlete: Athlete, powerReference: number | null): number | null {
  if (!lap.step_target_type || lap.step_target_low == null) return null;
  const lo = lap.step_target_low;
  const hi = lap.step_target_high ?? lo;
  const mid = (lo + hi) / 2;
  if (lap.step_target_type === "power" && lap.avg_power != null) {
    const targetWatts = lap.step_power_unit === "watts" ? mid : powerReference ? (mid / 100) * powerReference : null;
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

function mean(values: number[]): number | null {
  return values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : null;
}

export interface StepSummaryRow {
  key: string;
  label: string;
  target: string | null;
  color: string;
  count: number;
  avgDuration: number;
  avgDistanceKm: number;
  avgPower: number | null;
  avgHr: number | null;
  avgCompliancePct: number | null;
}

/** Identifies "the same step, wherever it occurs" for summarizeSteps' grouping - kind + target,
 * plus the step's own planned duration/distance when `collapseByTarget` is false (the default).
 * Deliberately NOT workout_step_id: a workout authored with the app's `repeat` construct reuses
 * one WorkoutStep row across every rep (so workout_step_id alone works there), but a workout with
 * the same interval typed out as several separate leaf steps (e.g. a manually-authored
 * 3x[65/75/85% FTP] ladder) gives each occurrence its own distinct row - grouping by raw id then
 * shows three near-identical "1x avg" cards instead of one real average.
 *
 * Duration/distance stay part of the signature by default so two steps that only coincidentally
 * share a %FTP target but are structurally different (a 20s 100%-FTP block vs a 600s 100%-FTP
 * block) aren't blended into one misleading average. `collapseByTarget` is an explicit opt-in
 * (see LapsTab's toggle) to merge purely by target instead, for a coarser "how did I do at each
 * intensity overall" view - the resulting avgDuration is then a genuine mix of very different
 * rep lengths, which is exactly what asking for this view means accepting. */
function stepSignature(lap: Lap, collapseByTarget: boolean): string {
  const parts = [lap.step_kind, lap.step_target_type, lap.step_target_low, lap.step_target_high, lap.step_power_unit];
  if (!collapseByTarget) parts.push(lap.step_duration, lap.step_distance);
  return parts.join("|");
}

/** One row per distinct step definition (see stepSignature), plus one "Other" row for any
 * unlinked laps (unmatched activities, original-source laps, or a trailing remainder beyond the
 * workout's plan). Order matches first appearance in `laps`, so it reads warmup -> work/rest ->
 * other, matching the activity's own flow. `powerReference` is passed straight through to
 * compliancePct/stepZonePct - see their docstrings for why it must be activity-date-scoped. */
export function summarizeSteps(
  laps: Lap[],
  athlete: Athlete,
  powerReference: number | null,
  collapseByTarget = false,
): StepSummaryRow[] {
  const order: string[] = [];
  const buckets = new Map<string, Lap[]>();
  for (const lap of laps) {
    const key = lap.workout_step_id != null ? stepSignature(lap, collapseByTarget) : "other";
    if (!buckets.has(key)) {
      buckets.set(key, []);
      order.push(key);
    }
    buckets.get(key)!.push(lap);
  }
  return order.map((key) => {
    const group = buckets.get(key)!;
    const first = group[0];
    const powers = group.map((l) => l.avg_power).filter((v): v is number => v != null);
    const hrs = group.map((l) => l.avg_hr).filter((v): v is number => v != null);
    const compliances = group.map((l) => compliancePct(l, athlete, powerReference)).filter((v): v is number => v != null);
    const zonePct = stepZonePct(first, powerReference);
    const compliance = mean(compliances);
    return {
      key,
      label: first.step_kind ? kindLabel(first.step_kind) : "Other",
      target: formatStepTarget(first),
      color: zonePct != null ? zoneColor(zonePct) : "var(--ink3)",
      count: group.length,
      avgDuration: mean(group.map((l) => l.duration)) ?? 0,
      avgDistanceKm: mean(group.map((l) => l.distance_km)) ?? 0,
      avgPower: mean(powers),
      avgHr: mean(hrs),
      avgCompliancePct: compliance != null ? Math.round(compliance) : null,
    };
  });
}
