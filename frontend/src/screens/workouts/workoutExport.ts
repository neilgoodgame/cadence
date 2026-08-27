import type { Athlete, WorkoutSport } from "../../api/types";
import { isGroup, kindLabel, stripIds, type Leaf, type Step } from "./workoutTree";

const FTP = 265; // fallback power reference (watts) when the athlete hasn't set a real one

function wattsRange(step: Leaf): { lo: number; hi: number } {
  const lo = step.target_low ?? 60;
  const hi = step.target_high ?? lo;
  return { lo: lo / 100, hi: hi / 100 };
}

function rawBounds(step: Leaf): { lo: number; hi: number } {
  const lo = step.target_low ?? 60;
  const hi = step.target_high ?? lo;
  return { lo, hi };
}

/** The athlete thresholds a step's target percentages are relative to (mirrors
 * backend/athletes/zones.py's THRESHOLD_FIELD_BY_ZONE_TYPE: bike_power -> ftp, run_power ->
 * critical_run_power, heart_rate -> lthr, pace -> threshold_pace). */
export interface ExportThresholds {
  ftp: number | null;
  criticalRunPower: number | null;
  lthr: number | null;
  thresholdPaceSecPerKm: number | null;
}

const EMPTY_THRESHOLDS: ExportThresholds = { ftp: null, criticalRunPower: null, lthr: null, thresholdPaceSecPerKm: null };

/** The real power threshold a %-based power step is relative to for this sport - bike power
 * and run power are different physical quantities, so they're kept in separate athlete fields. */
export function powerReferenceFor(sport: WorkoutSport, thresholds: ExportThresholds): number | null {
  return sport === "bike" ? thresholds.ftp : thresholds.criticalRunPower;
}

export function parseThresholdPaceSecPerKm(mmss: string | null | undefined): number | null {
  if (!mmss) return null;
  const parts = mmss.split(":");
  if (parts.length !== 2) return null;
  const [minutes, seconds] = parts.map(Number);
  if (!Number.isFinite(minutes) || !Number.isFinite(seconds)) return null;
  return minutes * 60 + seconds;
}

export function thresholdsFromAthlete(
  athlete: Pick<Athlete, "ftp" | "critical_run_power" | "lthr" | "threshold_pace"> | null | undefined,
): ExportThresholds {
  return {
    ftp: athlete?.ftp ?? null,
    criticalRunPower: athlete?.critical_run_power ?? null,
    lthr: athlete?.lthr ?? null,
    thresholdPaceSecPerKm: parseThresholdPaceSecPerKm(athlete?.threshold_pace),
  };
}

/** True if the workout has a step whose real threshold isn't set, meaning its TCX target falls
 * back to an approximate value (a placeholder power reference) instead of a real one. A
 * "watts"-unit power step is already absolute, so it never needs a reference for TCX. */
export function tcxHasApproximateTarget(steps: Step[], sport: WorkoutSport, thresholds: ExportThresholds): boolean {
  const hasRealPowerRef = powerReferenceFor(sport, thresholds) != null;
  const check = (list: Step[]): boolean =>
    list.some((s) => {
      if (isGroup(s)) return check(s.children as Step[]);
      if (s.target_type === "hr") return thresholds.lthr == null;
      if (s.target_type === "pace") return thresholds.thresholdPaceSecPerKm == null;
      if (s.target_type === "power" && s.power_unit === "watts") return false;
      return !hasRealPowerRef; // %FTP power, cadence, open
    });
  return check(steps);
}

/** Garmin TCX workouts always carry absolute values (watts/bpm/m-per-s), unlike Zwift's .zwo,
 * which stores power as a fraction of FTP that Zwift itself resolves on-device - see buildZwo.
 * `ftpOverride` only affects a "pct_ftp"-unit power step's watts conversion - a "watts"-unit
 * step is already absolute and exports unchanged regardless of override. */
export function buildTcx(
  name: string,
  sport: WorkoutSport,
  steps: Step[],
  thresholds: ExportThresholds = EMPTY_THRESHOLDS,
  ftpOverride?: number | null,
): string {
  let id = 1;
  const powerRef = ftpOverride ?? powerReferenceFor(sport, thresholds) ?? FTP;
  const target = (s: Leaf) => {
    const w = wattsRange(s);
    if (s.target_type === "hr" && thresholds.lthr != null) {
      const lo = Math.round(thresholds.lthr * w.lo);
      const hi = Math.round(thresholds.lthr * w.hi);
      return (
        `<Target xsi:type="HeartRate_t"><HeartRateZone xsi:type="CustomHeartRateZone_t">` +
        `<Low xsi:type="HeartRateInBeatsPerMinute_t"><Value>${lo}</Value></Low>` +
        `<High xsi:type="HeartRateInBeatsPerMinute_t"><Value>${hi}</Value></High></HeartRateZone></Target>`
      );
    }
    if (s.target_type === "pace" && thresholds.thresholdPaceSecPerKm != null) {
      // Speed (not pace) is what's linear in effort %: speed_mps = threshold_speed_mps * pct/100,
      // since pace itself is threshold's reciprocal (see zones.py's reference_for comment).
      const thresholdSpeedMps = 1000 / thresholds.thresholdPaceSecPerKm;
      const lo = (thresholdSpeedMps * w.lo).toFixed(2);
      const hi = (thresholdSpeedMps * w.hi).toFixed(2);
      return (
        `<Target xsi:type="Speed_t"><SpeedZone xsi:type="CustomSpeedZone_t">` +
        `<ViewAs xsi:type="SpeedType_t">Pace</ViewAs>` +
        `<LowInMetersPerSecond>${lo}</LowInMetersPerSecond><HighInMetersPerSecond>${hi}</HighInMetersPerSecond></SpeedZone></Target>`
      );
    }
    if (s.target_type === "power" && s.power_unit === "watts") {
      // Already absolute - exported as-is, no reference/override needed at all.
      const lo = Math.round(s.target_low ?? 0);
      const hi = Math.round(s.target_high ?? s.target_low ?? 0);
      return `<Target xsi:type="Power_t"><PowerZone xsi:type="CustomPowerZone_t"><Low><Value>${lo}</Value></Low><High><Value>${hi}</Value></High></PowerZone></Target>`;
    }
    // %FTP power (and cadence/open, and hr/pace without a real threshold set) - absolute watts
    // against the athlete's real sport-specific power reference (ftp for bike, critical_run_power
    // for run) or the override, falling back to a placeholder only when neither is set.
    return `<Target xsi:type="Power_t"><PowerZone xsi:type="CustomPowerZone_t"><Low><Value>${Math.round(powerRef * w.lo)}</Value></Low><High><Value>${Math.round(powerRef * w.hi)}</Value></High></PowerZone></Target>`;
  };
  const step = (name: string, s: Leaf, intensity: string) => {
    return (
      `      <Step xsi:type="Step_t">\n        <StepId>${id++}</StepId>\n        <Name>${name}</Name>\n` +
      `        <Duration xsi:type="Time_t"><Seconds>${s.duration || 0}</Seconds></Duration>\n` +
      `        <Intensity>${intensity}</Intensity>\n` +
      `        ${target(s)}\n      </Step>\n`
    );
  };
  const body: string[] = [];
  const emitTcx = (list: Step[]) => {
    for (const s of list) {
      if (!isGroup(s) && s.kind === "warmup") body.push(step("Warm-up", s, "Active"));
      else if (!isGroup(s) && s.kind === "cool") body.push(step("Cool-down", s, "Resting"));
      else if (isGroup(s)) {
        body.push(`      <Step xsi:type="Repeat_t">\n        <StepId>${id++}</StepId>\n        <Repetitions>${s.repeat}</Repetitions>\n`);
        const flat = (s.children as Step[]).every((c) => !isGroup(c));
        if (flat) {
          for (const c of s.children as Leaf[]) {
            body.push(
              `        <Child xsi:type="Step_t">${step(kindLabel(c.kind), c, c.kind === "rec" ? "Resting" : "Active")
                .replace("      <Step", "<Step")
                .trim()}</Child>\n`,
            );
          }
        } else {
          // nested group: TCX has no repeat-of-repeat, so inline instead of nesting further
          emitTcx(s.children as Step[]);
        }
        body.push("      </Step>\n");
      } else body.push(step(kindLabel((s as Leaf).kind), s as Leaf, (s as Leaf).kind === "rec" ? "Resting" : "Active"));
    }
  };
  emitTcx(steps);
  return (
    '<?xml version="1.0" encoding="UTF-8"?>\n<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">\n  <Workouts>\n' +
    `    <Workout Sport="${sport === "bike" ? "Biking" : "Running"}">\n      <Name>${name}</Name>\n${body.join("")}    </Workout>\n  </Workouts>\n</TrainingCenterDatabase>\n`
  );
}

/** Zwift's .zwo stores power as a fraction of FTP that Zwift resolves against whatever FTP is
 * set in the rider's own Zwift profile. A "pct_ftp"-unit step's stored percentage already *is*
 * that fraction, so by default it passes straight through unchanged (no athlete data needed at
 * all) - `ftpOverride` only re-bases it onto a different FTP than the workout's own (e.g. the
 * rider's Zwift FTP differs from their Cadence profile), via the athlete's real power reference
 * as the absolute-watts anchor. A "watts"-unit step has no fraction of its own at all - it
 * *requires* a reference (the override, or the athlete's real one, or the placeholder) to become
 * a fraction in the first place. */
export function buildZwo(
  name: string,
  sport: WorkoutSport,
  steps: Step[],
  thresholds: ExportThresholds = EMPTY_THRESHOLDS,
  ftpOverride?: number | null,
): string {
  const realRef = powerReferenceFor(sport, thresholds);
  const fraction = (s: Leaf, raw: number) => {
    if (s.power_unit === "watts") return raw / (ftpOverride ?? realRef ?? FTP);
    if (ftpOverride == null) return raw / 100;
    return ((realRef ?? FTP) * (raw / 100)) / ftpOverride;
  };
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    "<workout_file>",
    "  <author>Cadence</author>",
    `  <name>${name}</name>`,
    "  <description>Designed in Cadence. Non-power targets are approximated as %FTP.</description>",
    `  <sportType>${sport === "bike" ? "bike" : "run"}</sportType>`,
    "  <tags/>",
    "  <workout>",
  ];
  const seg = (tag: string, s: Leaf) => {
    const b = rawBounds(s);
    return `    <${tag} Duration="${s.duration || 0}" PowerLow="${fraction(s, b.lo).toFixed(2)}" PowerHigh="${fraction(s, b.hi).toFixed(2)}"/>`;
  };
  const emit = (list: Step[]) => {
    for (const s of list) {
      if (!isGroup(s) && s.kind === "warmup") lines.push(seg("Warmup", s));
      else if (!isGroup(s) && s.kind === "cool") lines.push(seg("Cooldown", s));
      else if (isGroup(s)) {
        const flat = (s.children as Step[]).every((c) => !isGroup(c));
        if (flat && s.children.length === 2) {
          const on = s.children[0] as Leaf;
          const off = s.children[1] as Leaf;
          const won = rawBounds(on);
          const woff = rawBounds(off);
          lines.push(
            `    <IntervalsT Repeat="${s.repeat}" OnDuration="${on.duration}" OffDuration="${off.duration}" OnPower="${fraction(on, won.lo).toFixed(2)}" OffPower="${fraction(off, woff.lo).toFixed(2)}"/>`,
          );
        } else {
          for (let r = 0; r < s.repeat; r++) emit(s.children as Step[]);
        }
      } else lines.push(seg("SteadyState", s as Leaf));
    }
  };
  emit(steps);
  lines.push("  </workout>", "</workout_file>", "");
  return lines.join("\n");
}

// Matches backend/workouts/test_fixtures/roundtrip_workout.schema.json - drop the
// downloaded file into backend/workouts/test_fixtures/roundtrip/ to add it as a
// round-trip test fixture for workouts.inference (see workouts/test_roundtrip.py).
export function buildFixtureJson(name: string, sport: WorkoutSport, steps: Step[]): string {
  return JSON.stringify({ name, sport, steps: stripIds(steps) }, null, 2) + "\n";
}

export function download(filename: string, content: string) {
  const blob = new Blob([content], { type: "application/xml" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
