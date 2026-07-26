import { useMemo, useState, type CSSProperties } from "react";
import type { WorkoutSport } from "../../api/types";
import type { Step } from "./workoutTree";
import { buildTcx, buildZwo, download } from "./workoutExport";

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
