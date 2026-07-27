import type { LeafStep, RepeatGroup, StepKind, WorkoutSport, WorkoutStep } from "../../api/types";

// Inverse of workoutExport.ts's buildZwo, extended to tolerate real Zwift-authored files
// (Ramp/FreeRide/MaxEffort, Cadence targets) rather than just our own output. Zwift stores
// power as a fraction of FTP (0.65 = 65%); WorkoutStep stores %FTP, so every Power* attribute
// is *100 on the way in, matching wattsRange()'s /100 on the way out.
const WORK_THRESHOLD_PCT = 85; // matches backend/workouts/inference.py's WORK_THRESHOLD_PCT

function pctFromFraction(value: string | null): number | null {
  if (value === null || value === "") return null;
  const n = Number(value);
  return Number.isFinite(n) ? Math.round(n * 100) : null;
}

function intAttr(el: Element, name: string): number {
  return Math.round(Number(el.getAttribute(name) ?? "0")) || 0;
}

function classifyByPower(low: number | null, high: number | null): StepKind {
  const pct = low === null ? 100 : (low + (high ?? low)) / 2;
  return pct >= WORK_THRESHOLD_PCT ? "block" : "rec";
}

function leaf(kind: StepKind, duration: number, low: number | null, high: number | null, cadence: string | null): LeafStep {
  const cadenceValue = cadence !== null && cadence !== "" ? Math.round(Number(cadence)) : null;
  return {
    kind,
    end_type: "time",
    duration,
    distance: null,
    target_type: low === null ? "open" : "power",
    target_low: low,
    target_high: high ?? low,
    target2_type: cadenceValue !== null ? "cadence" : "none",
    target2_low: cadenceValue,
    target2_high: cadenceValue,
    note: "",
  };
}

export function parseZwoFile(xmlText: string): { name: string; sport: WorkoutSport; steps: WorkoutStep[] } {
  const doc = new DOMParser().parseFromString(xmlText, "application/xml");
  if (doc.querySelector("parsererror")) {
    throw new Error("This file isn't valid .zwo XML.");
  }

  const name = doc.querySelector("name")?.textContent?.trim() || "Imported workout";
  const sportRaw = doc.querySelector("sportType")?.textContent?.trim().toLowerCase();
  const sport: WorkoutSport = sportRaw === "run" ? "run" : "bike";

  const workoutEl = doc.querySelector("workout");
  if (!workoutEl || workoutEl.children.length === 0) {
    throw new Error("No <workout> segments found in this file.");
  }

  const steps: WorkoutStep[] = [];
  for (const el of Array.from(workoutEl.children)) {
    const tag = el.tagName.toLowerCase();
    const duration = intAttr(el, "Duration");

    if (tag === "warmup" || tag === "cooldown" || tag === "ramp") {
      const low = pctFromFraction(el.getAttribute("PowerLow"));
      const high = pctFromFraction(el.getAttribute("PowerHigh"));
      const kind: StepKind = tag === "warmup" ? "warmup" : tag === "cooldown" ? "cool" : classifyByPower(low, high);
      steps.push(leaf(kind, duration, low, high, el.getAttribute("Cadence")));
    } else if (tag === "steadystate") {
      const low = pctFromFraction(el.getAttribute("Power") ?? el.getAttribute("PowerLow"));
      const high = pctFromFraction(el.getAttribute("Power") ?? el.getAttribute("PowerHigh")) ?? low;
      steps.push(leaf(classifyByPower(low, high), duration, low, high, el.getAttribute("Cadence")));
    } else if (tag === "freeride") {
      steps.push(leaf("rec", duration, null, null, el.getAttribute("Cadence")));
    } else if (tag === "maxeffort") {
      steps.push(leaf("block", duration, null, null, null));
    } else if (tag === "intervalst") {
      const repeat = intAttr(el, "Repeat") || 1;
      const onLow = pctFromFraction(el.getAttribute("OnPower"));
      const offLow = pctFromFraction(el.getAttribute("OffPower"));
      const group: RepeatGroup = {
        kind: "repeat",
        repeat,
        note: "",
        children: [
          leaf("block", intAttr(el, "OnDuration"), onLow, onLow, el.getAttribute("Cadence")),
          leaf("rec", intAttr(el, "OffDuration"), offLow, offLow, el.getAttribute("CadenceResting") ?? el.getAttribute("Cadence")),
        ],
      };
      steps.push(group);
    }
    // Unrecognized tags (e.g. a stray <textevent>) are skipped rather than failing the import.
  }

  if (steps.length === 0) {
    throw new Error("No recognizable workout segments (Warmup/SteadyState/IntervalsT/...) found in this file.");
  }

  return { name, sport, steps };
}

// Inverse of workoutExport.ts's buildFixtureJson - loads the {name, sport, steps} shape
// documented in backend/workouts/test_fixtures/roundtrip_workout.schema.json. Deliberately
// light validation (just enough for a clear error message): the create-workout API is the
// real gate on step-tree correctness, same as any other workout this screen can produce.
export function parseFixtureJson(jsonText: string): { name: string; sport: WorkoutSport; steps: WorkoutStep[] } {
  let data: unknown;
  try {
    data = JSON.parse(jsonText);
  } catch {
    throw new Error("This file isn't valid JSON.");
  }

  if (typeof data !== "object" || data === null) {
    throw new Error('Expected a JSON object with "name", "sport", and "steps".');
  }
  const obj = data as Record<string, unknown>;

  if (obj.sport !== "bike" && obj.sport !== "run") {
    throw new Error('"sport" must be "bike" or "run".');
  }
  if (!Array.isArray(obj.steps) || obj.steps.length === 0) {
    throw new Error('Expected a non-empty "steps" array.');
  }

  const name = typeof obj.name === "string" && obj.name.trim() ? obj.name : "Imported workout";
  return { name, sport: obj.sport, steps: obj.steps as WorkoutStep[] };
}
