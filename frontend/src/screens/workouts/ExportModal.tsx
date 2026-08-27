import { useMemo, useState, type CSSProperties } from "react";
import type { WorkoutSport } from "../../api/types";
import { useAuth } from "../../auth/AuthContext";
import { isGroup, type Step } from "./workoutTree";
import { buildFixtureJson, buildTcx, buildZwo, download, powerReferenceFor, tcxHasApproximateTarget, thresholdsFromAthlete } from "./workoutExport";

function hasPowerStep(steps: Step[]): boolean {
  return steps.some((s) => (isGroup(s) ? hasPowerStep(s.children as Step[]) : s.target_type === "power"));
}

export function ExportModal({ name, sport, steps, onClose }: { name: string; sport: WorkoutSport; steps: Step[]; onClose: () => void }) {
  const { user } = useAuth();
  const thresholds = thresholdsFromAthlete(user);
  const powerReference = powerReferenceFor(sport, thresholds);
  const [format, setFormat] = useState<"zwo" | "tcx" | "fixture">("zwo");
  const [ftpOverrideInput, setFtpOverrideInput] = useState("");
  const parsedOverride = ftpOverrideInput.trim() === "" ? null : Number(ftpOverrideInput);
  const ftpOverride = parsedOverride != null && Number.isFinite(parsedOverride) ? parsedOverride : null;
  const showOverride = hasPowerStep(steps) && format !== "fixture";
  const preview = useMemo(() => {
    if (format === "zwo") return buildZwo(name, sport, steps, thresholds, ftpOverride);
    if (format === "tcx") return buildTcx(name, sport, steps, thresholds, ftpOverride);
    return buildFixtureJson(name, sport, steps);
  }, [format, name, sport, steps, thresholds, ftpOverride]);
  const filename = format === "zwo" ? "workout.zwo" : format === "tcx" ? "workout.tcx" : "workout.json";
  const target = format === "zwo" ? "Zwift" : format === "tcx" ? "Garmin Connect" : "test fixture";
  const powerRefLabel = sport === "bike" ? "FTP" : "critical power";
  const note =
    format === "zwo"
      ? powerReference != null
        ? `Drop the .zwo into Documents/Zwift/Workouts/<id>/ or import via the companion. %FTP-defined power steps pass straight through by default; watts-defined steps are converted to a fraction using your real ${powerRefLabel} - set an override below to use a different one for this export.`
        : `Drop the .zwo into Documents/Zwift/Workouts/<id>/ or import via the companion. Non-power targets approximated as %FTP. Set your ${powerRefLabel} in Settings to enable the override below.`
      : format === "tcx"
        ? tcxHasApproximateTarget(steps, sport, thresholds)
          ? `Garmin Connect → Training → Workouts → Import. Targets use your real LTHR/threshold pace/${powerRefLabel} where set; steps without one fall back to an approximate placeholder - set them in Settings for accuracy.`
          : "Garmin Connect → Training → Workouts → Import. Heart-rate, pace, and power targets all use your real thresholds."
        : "For backend testing: drop the file into backend/workouts/test_fixtures/roundtrip/ to add it as a round-trip inference test case.";

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
            <div onClick={() => setFormat("fixture")} style={segBtn(format === "fixture")}>
              Test fixture · .json
            </div>
          </div>
          <div style={{ fontSize: 12.5, color: "var(--ink2)" }}>{note}</div>
          {showOverride && (
            <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12.5, color: "var(--ink2)" }}>
              Override {powerRefLabel} for this export (W)
              <input
                type="number"
                min={1}
                placeholder={powerReference != null ? String(powerReference) : "not set"}
                disabled={powerReference == null}
                value={ftpOverrideInput}
                onChange={(e) => setFtpOverrideInput(e.target.value)}
                style={{
                  width: 90,
                  padding: "5px 9px",
                  borderRadius: 7,
                  border: "1px solid var(--line)",
                  background: "var(--elev)",
                  color: "var(--ink)",
                  fontSize: 12.5,
                  opacity: powerReference == null ? 0.5 : 1,
                }}
              />
            </label>
          )}
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
