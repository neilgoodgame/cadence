import type { LeafStep, RepeatGroup, StepKind, Target2Type, TargetType, WorkoutStep, WorkoutSport } from "../../api/types";

// Pure tree/calc/format helpers for the workout Build-mode editor. Ported from the
// design prototype (Workout Designer.dc.html) so the frontend, Python, and Java
// duration/TSS math all agree. Steps form a tree: leaves (warmup/block/rec/cool)
// or `repeat` groups whose `children` may themselves be nested `repeat` groups.

let uid = 100;
export function nextId(): string {
  return `s${uid++}`;
}

// Generic (rather than a fixed `step is RepeatGroup` predicate) so it narrows
// correctly for whichever union it's called with: a client-side `Step` narrows to
// `Group` (children: Step[]), a plain wire-shape `WorkoutStep` narrows to
// `RepeatGroup` (children: WorkoutStep[]) - and, unlike an overloaded signature,
// the `!isGroup(step)` else-branch narrows correctly too (to `Leaf`/`LeafStep`).
export function isGroup<T extends { kind: string }>(step: T): step is Extract<T, { kind: "repeat" }> {
  return step.kind === "repeat";
}

// ---------- tree helpers (recursive, id-keyed via a WeakMap-free approach: we
// track ids out-of-band since WorkoutStep itself has no id field on the wire) ----------
// The editor keeps ids alongside steps in a parallel `Identified<WorkoutStep>` wrapper
// (see WithId below) so client-side selection/reorder can address a specific node
// without the API needing to know about client ids.
export interface WithId {
  id: string;
}
export type Leaf = LeafStep & WithId;
// `Step`'s recursive union has to be built from `Leaf`/`Group` directly (not
// `WorkoutStep & WithId`) so its "repeat" branch's `children` is `Step[]` - if it
// were derived from `WorkoutStep`, the repeat branch would just be `RepeatGroup &
// WithId`, whose `children: WorkoutStep[]` doesn't carry ids, breaking `isGroup`'s
// narrowing (and every recursive helper below that reads `.children` off a Group).
export type Group = Omit<RepeatGroup, "children"> & WithId & { children: Step[] };
export type Step = Leaf | Group;

export function mapSteps(steps: Step[], id: string, fn: (step: Step) => Step): Step[] {
  return steps.map((s) => {
    if (s.id === id) return fn(s);
    if (isGroup(s)) return { ...s, children: mapSteps(s.children as Step[], id, fn) } as Group;
    return s;
  });
}

export function findStep(steps: Step[], id: string | null): Step | null {
  if (id === null) return null;
  for (const s of steps) {
    if (s.id === id) return s;
    if (isGroup(s)) {
      const found = findStep(s.children as Step[], id);
      if (found) return found;
    }
  }
  return null;
}

export function removeStep(steps: Step[], id: string): Step[] {
  return steps
    .filter((s) => s.id !== id)
    .map((s) => (isGroup(s) ? ({ ...s, children: removeStep(s.children as Step[], id) } as Group) : s));
}

export function duplicateStep(steps: Step[], id: string): Step[] {
  const clone = (s: Step): Step =>
    isGroup(s) ? ({ ...s, id: nextId(), children: (s.children as Step[]).map(clone) } as Group) : { ...s, id: nextId() };
  const rec = (arr: Step[]): Step[] => {
    const out: Step[] = [];
    for (const s of arr) {
      if (s.id === id) {
        out.push(s, clone(s));
        continue;
      }
      if (isGroup(s)) {
        out.push({ ...s, children: rec(s.children as Step[]) } as Group);
        continue;
      }
      out.push(s);
    }
    return out;
  };
  return rec(steps);
}

export function moveStep(steps: Step[], id: string, dir: -1 | 1): Step[] {
  const swap = (arr: Step[], i: number, j: number): Step[] => {
    const a = arr.slice();
    [a[i], a[j]] = [a[j], a[i]];
    return a;
  };
  const idx = steps.findIndex((s) => s.id === id);
  if (idx > -1) {
    const j = idx + dir;
    if (j < 0 || j >= steps.length) return steps;
    return swap(steps, idx, j);
  }
  return steps.map((s) => (isGroup(s) ? ({ ...s, children: moveStep(s.children as Step[], id, dir) } as Group) : s));
}

// ---------- duration / TSS (mirrors backend/workouts/calculations.py and
// WorkoutCalculations.java exactly) ----------
function leafDuration(step: LeafStep): number {
  return step.end_type === "time" ? step.duration || 0 : 0;
}

export function totalDuration(steps: WorkoutStep[]): number {
  return steps.reduce(
    (sum, s) => (isGroup(s) ? sum + totalDuration(s.children) * (s.repeat || 1) : sum + leafDuration(s)),
    0,
  );
}

function leafTss(step: LeafStep): number {
  const lo = step.target_low ?? 60;
  const hi = step.target_high ?? lo;
  const avg = (lo + hi) / 2;
  const hours = (step.duration || 0) / 3600;
  if (step.target_type === "power") return hours * Math.pow(avg / 100, 2) * 100;
  if (step.target_type === "open") return hours * 55;
  return hours * (avg / 100) * 80;
}

function tssSum(steps: WorkoutStep[]): number {
  return steps.reduce((sum, s) => (isGroup(s) ? sum + tssSum(s.children) * (s.repeat || 1) : sum + leafTss(s)), 0);
}

export function totalTss(steps: WorkoutStep[]): number {
  return Math.round(tssSum(steps));
}

export function stepCount(steps: WorkoutStep[]): number {
  return steps.reduce((n, s) => n + (isGroup(s) ? stepCount(s.children) * (s.repeat || 1) : 1), 0);
}

export function flattenLeaves(steps: WorkoutStep[]): LeafStep[] {
  let out: LeafStep[] = [];
  for (const s of steps) {
    if (isGroup(s)) {
      for (let r = 0; r < (s.repeat || 1); r++) out = out.concat(flattenLeaves(s.children));
    } else {
      out.push(s);
    }
  }
  return out;
}

// Id-preserving variant of flattenLeaves, for chart click-to-select.
export function flattenLeafSteps(steps: Step[]): Leaf[] {
  let out: Leaf[] = [];
  for (const s of steps) {
    if (isGroup(s)) {
      for (let r = 0; r < (s.repeat || 1); r++) out = out.concat(flattenLeafSteps(s.children as Step[]));
    } else {
      out.push(s);
    }
  }
  return out;
}

// ---------- formatting ----------
export function fmtDuration(sec: number): string {
  sec = Math.max(0, Math.round(sec || 0));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  return [h, m, s].map((v) => String(v).padStart(2, "0")).join(":");
}

export function parseDuration(label: string): number {
  const parts = String(label)
    .split(":")
    .map((n) => parseInt(n, 10) || 0);
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
  if (parts.length === 2) return parts[0] * 60 + parts[1];
  return parts[0] || 0;
}

const FTP = 265;
const MAXHR = 188;
const THRESH_PACE = 270; // sec/km at threshold

function fmtPace(secPerKm: number): string {
  const m = Math.floor(secPerKm / 60);
  const s = Math.round(secPerKm % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}

export function zoneColor(pct: number): string {
  if (pct < 56) return "#8a94a6";
  if (pct < 76) return "#3d7fd6";
  if (pct < 91) return "#2fa66a";
  if (pct < 106) return "#f0a02e";
  if (pct < 121) return "#ec4a26";
  return "#c4332a";
}

export function kindLabel(k: StepKind | "repeat"): string {
  return { warmup: "Warm-up", block: "Work block", rec: "Recovery", cool: "Cooldown", repeat: "Repeat" }[k] ?? k;
}

export interface TargetInfo {
  primary: string;
  secondary: string | null;
  color: string;
}

export function targetInfo(step: LeafStep): TargetInfo {
  if (!step.target_type || step.target_type === "open") return { primary: "Open · manual lap", secondary: null, color: "#8a94a6" };
  const lo = step.target_low ?? 0;
  const hi = step.target_high ?? lo;
  const avg = (lo + hi) / 2;
  let primary: string;
  let color: string;
  if (step.target_type === "power") {
    const w = (p: number) => Math.round((FTP * p) / 100);
    primary = lo === hi ? `${lo}% FTP · ${w(lo)}W` : `${lo}→${hi}% FTP`;
    color = zoneColor(avg);
  } else if (step.target_type === "hr") {
    const b = (p: number) => Math.round((MAXHR * p) / 100);
    primary = lo === hi ? `${lo}% max HR · ${b(lo)}bpm` : `${lo}→${hi}% max HR`;
    color = zoneColor(avg);
  } else if (step.target_type === "pace") {
    const pc = (p: number) => `${fmtPace(THRESH_PACE / (p / 100))}/km`;
    primary = lo === hi ? `${lo}% pace · ${pc(lo)}` : `${lo}→${hi}% pace`;
    color = zoneColor(avg);
  } else {
    primary = `${lo === hi ? lo : `${lo}–${hi}`} rpm`;
    color = "#3d7fd6";
  }
  let secondary: string | null = null;
  if (step.target2_type === "cadence") secondary = `${step.target2_low}–${step.target2_high} rpm cadence`;
  return { primary, secondary, color };
}

// Display only - shows km once it's the more readable unit, but only metres is ever stored
// (see StepDrawer's DistanceInput), so this doesn't reflect which unit a step was authored in.
function fmtDistanceMeters(meters: number): string {
  if (meters >= 1000) return `${Math.round((meters / 1000) * 100) / 100} km`;
  return `${meters} m`;
}

export function stepDetail(step: LeafStep): string {
  const dur =
    step.end_type === "time"
      ? fmtDuration(step.duration || 0)
      : step.end_type === "distance"
        ? fmtDistanceMeters(step.distance || 0)
        : "Manual";
  const t = targetInfo(step);
  return dur + " · " + t.primary + (t.secondary ? " · " + t.secondary : "");
}

// ---------- defaults ----------
const LEAF_PRESETS: Record<string, { duration: number; target_low: number; target_high: number }> = {
  warmup: { duration: 600, target_low: 50, target_high: 70 },
  block: { duration: 300, target_low: 100, target_high: 100 },
  rec: { duration: 120, target_low: 50, target_high: 55 },
  cool: { duration: 300, target_low: 55, target_high: 40 },
};

export function defaultLeaf(kind: "warmup" | "block" | "rec" | "cool", sport: WorkoutSport = "bike"): Leaf {
  const p = LEAF_PRESETS[kind] ?? LEAF_PRESETS.block;
  return {
    id: nextId(),
    kind,
    end_type: "time",
    duration: p.duration,
    distance: null,
    target_type: sport === "bike" ? "power" : "pace",
    target_low: p.target_low,
    target_high: p.target_high,
    target2_type: "none" as Target2Type,
    target2_low: 85,
    target2_high: 95,
    note: "",
  };
}

export function defaultGroup(sport: WorkoutSport = "bike"): Group {
  return {
    id: nextId(),
    kind: "repeat",
    repeat: 4,
    note: "",
    children: [
      {
        id: nextId(),
        kind: "block",
        end_type: "time",
        duration: 300,
        distance: null,
        target_type: (sport === "bike" ? "power" : "pace") as TargetType,
        target_low: 100,
        target_high: 100,
        target2_type: "none" as Target2Type,
        target2_low: 85,
        target2_high: 95,
        note: "",
      },
      {
        id: nextId(),
        kind: "rec",
        end_type: "time",
        duration: 120,
        distance: null,
        target_type: (sport === "bike" ? "power" : "pace") as TargetType,
        target_low: 50,
        target_high: 55,
        target2_type: "none" as Target2Type,
        target2_low: 85,
        target2_high: 95,
        note: "",
      },
    ],
  };
}

// ---------- strip client-only ids before sending to the API ----------
export function stripIds(steps: Step[]): WorkoutStep[] {
  return steps.map((s): WorkoutStep => {
    if (isGroup(s)) {
      return { kind: "repeat", repeat: s.repeat, note: s.note, children: stripIds(s.children as Step[]) };
    }
    const { kind, end_type, duration, distance, target_type, target_low, target_high, target2_type, target2_low, target2_high, note } = s;
    return { kind, end_type, duration, distance, target_type, target_low, target_high, target2_type, target2_low, target2_high, note };
  });
}

export function withIds(steps: WorkoutStep[]): Step[] {
  return steps.map((s) => {
    if (isGroup(s)) {
      return { ...s, id: nextId(), children: withIds(s.children) } as Group;
    }
    return { ...s, id: nextId() } as Leaf;
  });
}

// ---------- templates ----------
export interface WorkoutTemplate {
  name: string;
  desc: string;
  build: () => Step[];
}

function leafTpl(
  kind: StepKind,
  duration: number,
  targetType: TargetType,
  low: number,
  high: number,
  target2Type?: Target2Type,
  target2Low?: number,
  target2High?: number,
): Leaf {
  return {
    id: nextId(),
    kind,
    end_type: "time",
    duration,
    distance: null,
    target_type: targetType,
    target_low: low,
    target_high: high,
    target2_type: target2Type ?? "none",
    target2_low: target2Low ?? 85,
    target2_high: target2High ?? 95,
    note: "",
  };
}

function intervalGroup(
  sport: WorkoutSport,
  reps: number,
  onDuration: number,
  onLow: number,
  onHigh: number,
  offDuration: number,
  offLow: number,
  offHigh: number,
  cadence?: { low: number; high: number },
): Group {
  const targetType: TargetType = sport === "bike" ? "power" : "pace";
  return {
    id: nextId(),
    kind: "repeat",
    repeat: reps,
    note: "",
    children: [
      leafTpl("block", onDuration, targetType, onLow, onHigh, cadence ? "cadence" : "none", cadence?.low, cadence?.high),
      leafTpl("rec", offDuration, targetType, offLow, offHigh),
    ],
  };
}

export function templatesFor(sport: WorkoutSport): WorkoutTemplate[] {
  if (sport === "bike") {
    return [
      { name: "VO2 5×5", desc: "5×5min @118% FTP, 3min recovery", build: () => [intervalGroup("bike", 5, 300, 118, 118, 180, 55, 55, { low: 90, high: 100 })] },
      { name: "Threshold 3×10", desc: "3×10min @100% FTP, 5min recovery", build: () => [intervalGroup("bike", 3, 600, 100, 100, 300, 60, 60)] },
      { name: "Tempo 20min", desc: "Single steady block @88% FTP", build: () => [leafTpl("block", 1200, "power", 88, 88)] },
      { name: "Endurance 90min", desc: "Long steady ride @68% FTP", build: () => [leafTpl("block", 5400, "power", 68, 68)] },
    ];
  }
  return [
    { name: "Easy run 45min", desc: "45min @68% threshold pace, cadence 170–180", build: () => [leafTpl("block", 2700, "pace", 68, 68, "cadence", 170, 180)] },
    { name: "Tempo run 25min", desc: "Steady @92% threshold pace", build: () => [leafTpl("block", 1500, "pace", 92, 92)] },
    { name: "6×3min intervals", desc: "6×3min @112% pace, 2min jog recovery", build: () => [intervalGroup("run", 6, 180, 112, 112, 120, 60, 60)] },
    { name: "Long run progressive", desc: "60min pace build 70%→85%", build: () => [leafTpl("block", 3600, "pace", 70, 85)] },
  ];
}
