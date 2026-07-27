import type { WorkoutSport } from "../../api/types";
import { isGroup, kindLabel, stripIds, type Leaf, type Step } from "./workoutTree";

const FTP = 265; // approximate reference threshold for the TCX watts preview

function wattsRange(step: Leaf): { lo: number; hi: number } {
  const lo = step.target_low ?? 60;
  const hi = step.target_high ?? lo;
  // Non-power targets have no watts equivalent, so they're approximated as %FTP
  // on export too - same simplification the prototype and backend TSS calc use.
  return { lo: lo / 100, hi: hi / 100 };
}

export function buildZwo(name: string, sport: WorkoutSport, steps: Step[]): string {
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
    const w = wattsRange(s);
    return `    <${tag} Duration="${s.duration || 0}" PowerLow="${w.lo.toFixed(2)}" PowerHigh="${w.hi.toFixed(2)}"/>`;
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
          const won = wattsRange(on);
          const woff = wattsRange(off);
          lines.push(
            `    <IntervalsT Repeat="${s.repeat}" OnDuration="${on.duration}" OffDuration="${off.duration}" OnPower="${won.lo.toFixed(2)}" OffPower="${woff.lo.toFixed(2)}"/>`,
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

export function buildTcx(name: string, sport: WorkoutSport, steps: Step[]): string {
  let id = 1;
  const step = (name: string, s: Leaf, intensity: string) => {
    const w = wattsRange(s);
    return (
      `      <Step xsi:type="Step_t">\n        <StepId>${id++}</StepId>\n        <Name>${name}</Name>\n` +
      `        <Duration xsi:type="Time_t"><Seconds>${s.duration || 0}</Seconds></Duration>\n` +
      `        <Intensity>${intensity}</Intensity>\n` +
      `        <Target xsi:type="Power_t"><PowerZone xsi:type="CustomPowerZone_t"><Low><Value>${Math.round(FTP * w.lo)}</Value></Low><High><Value>${Math.round(FTP * w.hi)}</Value></High></PowerZone></Target>\n      </Step>\n`
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
