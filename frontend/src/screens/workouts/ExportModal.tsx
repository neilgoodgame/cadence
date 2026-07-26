import { useMemo, useState, type CSSProperties } from "react";
import type { WorkoutSport } from "../../api/types";
import { isGroup, kindLabel, type Leaf, type Step } from "./workoutTree";

const FTP = 265; // approximate reference threshold for the TCX watts preview

function wattsRange(step: Leaf): { lo: number; hi: number } {
  const lo = step.target_low ?? 60;
  const hi = step.target_high ?? lo;
  // Non-power targets have no watts equivalent, so they're approximated as %FTP
  // on export too - same simplification the prototype and backend TSS calc use.
  return { lo: lo / 100, hi: hi / 100 };
}

function buildZwo(name: string, sport: WorkoutSport, steps: Step[]): string {
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

function buildTcx(name: string, sport: WorkoutSport, steps: Step[]): string {
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

function download(filename: string, content: string) {
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

export function ExportModal({ name, sport, steps, onClose }: { name: string; sport: WorkoutSport; steps: Step[]; onClose: () => void }) {
  const [format, setFormat] = useState<"zwo" | "tcx">("zwo");
  const preview = useMemo(() => (format === "zwo" ? buildZwo(name, sport, steps) : buildTcx(name, sport, steps)), [format, name, sport, steps]);
  const filename = format === "zwo" ? "workout.zwo" : "workout.tcx";
  const target = format === "zwo" ? "Zwift" : "Garmin Connect";
  const note =
    format === "zwo"
      ? "Drop the .zwo into Documents/Zwift/Workouts/<id>/ or import via the companion. Non-power targets approximated as %FTP."
      : "Garmin Connect → Training → Workouts → Import. Non-power targets approximated as %FTP.";

  const segBtn = (active: boolean): CSSProperties => ({
    flex: 1,
    textAlign: "center",
    padding: "5px 11px",
    fontSize: 12,
    fontWeight: 600,
    borderRadius: 7,
    cursor: "pointer",
    color: active ? "var(--ink)" : "var(--ink3)",
    background: active ? "var(--card)" : "transparent",
  });

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100, padding: 32 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ background: "var(--card)", borderRadius: 14, width: 680, maxWidth: "100%", maxHeight: "88vh", display: "flex", flexDirection: "column", overflow: "hidden" }}
      >
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "18px 22px", borderBottom: "1px solid var(--line)" }}>
          <div>
            <div style={{ fontSize: 16, fontWeight: 800, color: "var(--ink)" }}>Export workout</div>
            <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 1 }}>
              {name} · for {target}
            </div>
          </div>
          <button onClick={onClose} style={{ width: 30, height: 30, borderRadius: 8, border: "1px solid var(--line)", background: "none", color: "var(--ink3)", cursor: "pointer", fontSize: 18 }}>
            ×
          </button>
        </div>
        <div style={{ padding: "18px 22px", display: "flex", flexDirection: "column", gap: 14, minHeight: 0 }}>
          <div style={{ display: "flex", gap: 4, background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 9, padding: 3, width: "fit-content" }}>
            <div onClick={() => setFormat("zwo")} style={segBtn(format === "zwo")}>
              Zwift · .zwo
            </div>
            <div onClick={() => setFormat("tcx")} style={segBtn(format === "tcx")}>
              Garmin · .tcx
            </div>
          </div>
          <div style={{ fontSize: 12.5, color: "var(--ink2)" }}>{note}</div>
          <div style={{ background: "#0c0d10", border: "1px solid var(--line)", borderRadius: 11, overflow: "auto", maxHeight: 300 }}>
            <pre style={{ margin: 0, padding: "15px 17px", fontFamily: "monospace", fontSize: 11.5, lineHeight: 1.55, color: "#c9c9c9", whiteSpace: "pre" }}>{preview}</pre>
          </div>
        </div>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 14, padding: "14px 22px", borderTop: "1px solid var(--line)", background: "var(--elev)" }}>
          <span style={{ fontFamily: "monospace", fontSize: 12, color: "var(--ink3)" }}>{filename}</span>
          <div style={{ display: "flex", gap: 9 }}>
            <button
              onClick={() => navigator.clipboard.writeText(preview).catch(() => {})}
              style={{ padding: "9px 16px", borderRadius: 9, border: "1px solid var(--line)", background: "var(--card)", fontSize: 13, fontWeight: 600, color: "var(--ink2)", cursor: "pointer" }}
            >
              Copy
            </button>
            <button
              onClick={() => download(filename, preview)}
              style={{ padding: "9px 18px", borderRadius: 9, border: "none", background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, cursor: "pointer" }}
            >
              Download
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
