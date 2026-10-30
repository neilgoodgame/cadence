import { useMemo, useState } from "react";
import { computeRacePlan, type SplitStrategy } from "../../lib/racePlan";
import { formatDuration, formatPace, parseDuration } from "../../lib/format";
import type { Race } from "../../api/types";

const CARBS_OPTIONS = [45, 60, 75, 90];

function segStyle(active: boolean): React.CSSProperties {
  return {
    padding: "5px 11px",
    fontSize: 11.5,
    fontWeight: 600,
    borderRadius: 7,
    cursor: "pointer",
    color: active ? "var(--ink)" : "var(--ink3)",
    background: active ? "var(--card)" : "transparent",
    boxShadow: active ? "0 1px 2px rgba(0,0,0,0.14)" : "none",
  };
}

function tile(label: string, value: string, sub?: string) {
  return (
    <div style={{ flex: 1, minWidth: 110, padding: "10px 14px", borderRadius: 10, background: "var(--canvas-solid)" }}>
      <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ink3)" }}>{label}</div>
      <div className="mono" style={{ fontSize: 16, fontWeight: 700, marginTop: 2 }}>{value}</div>
      {sub && <div style={{ fontSize: 11, color: "var(--ink3)", marginTop: 1 }}>{sub}</div>}
    </div>
  );
}

/** README §4.2.5 "Pacing and fuelling plan" card - shown for an upcoming run A/B race with a
 * goal. All math lives in lib/racePlan.ts; this just renders it against the controls. */
export function PacingPlanCard({ race, sweatRateLPerH }: { race: Race; sweatRateLPerH: number }) {
  const [strategy, setStrategy] = useState<SplitStrategy>("even");
  const [carbsGPerH, setCarbsGPerH] = useState(60);

  const goalS = race.goal_time ? parseDuration(race.goal_time) : null;

  const plan = useMemo(() => {
    if (!goalS || !race.distance_km) return null;
    return computeRacePlan({ distanceKm: race.distance_km, goalTimeS: goalS, strategy, carbsGPerH, sweatRateLPerH });
  }, [goalS, race.distance_km, strategy, carbsGPerH, sweatRateLPerH]);

  if (!plan) {
    return (
      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>Set a goal time to build a pacing and fuelling plan.</div>
      </div>
    );
  }

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 10, marginBottom: 14 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>Pacing and fuelling plan</h2>
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
          <div style={{ display: "flex", gap: 2, background: "var(--canvas-solid)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
            <div onClick={() => setStrategy("even")} style={segStyle(strategy === "even")}>Even</div>
            <div onClick={() => setStrategy("negative")} style={segStyle(strategy === "negative")}>Negative split</div>
          </div>
          <div style={{ display: "flex", gap: 2, background: "var(--canvas-solid)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
            {CARBS_OPTIONS.map((g) => (
              <div key={g} onClick={() => setCarbsGPerH(g)} style={segStyle(carbsGPerH === g)}>{g} g/h</div>
            ))}
          </div>
        </div>
      </div>

      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", marginBottom: 18 }}>
        {tile("Halves", `${formatDuration(plan.halves.firstS)} / ${formatDuration(plan.halves.secondS)}`)}
        {tile("Carbs", `${plan.fuel.gels.length} gel${plan.fuel.gels.length === 1 ? "" : "s"}`, `25 g every ${plan.fuel.intervalMin.toFixed(0)} min`)}
        {tile("Fluid", `${plan.fluidLPerH.toFixed(2)} L/h`)}
        {tile("Sodium", `${plan.sodiumMgPerH.toFixed(0)} mg/h`)}
      </div>

      <div style={{ maxHeight: 320, overflowY: "auto" }}>
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12.5 }}>
          <thead>
            <tr style={{ position: "sticky", top: 0, background: "var(--card)", textAlign: "left" }}>
              {["Split", "Target", "Pace", "Clock", "Fuel & drink"].map((h) => (
                <th key={h} style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.05em", textTransform: "uppercase", color: "var(--ink3)", padding: "6px 8px", borderBottom: "1px solid var(--line)" }}>
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {plan.table.map((row) => (
              <tr key={row.splitKm} style={{ borderBottom: "1px solid var(--line)" }}>
                <td className="mono" style={{ padding: "6px 8px", fontWeight: row.isFinish ? 700 : 400 }}>
                  {row.isFinish ? "Finish" : `${row.splitKm} km`}
                </td>
                <td className="mono" style={{ padding: "6px 8px" }}>{formatDuration(row.targetS)}</td>
                <td className="mono" style={{ padding: "6px 8px" }}>{formatPace(row.paceSPerKm)}</td>
                <td className="mono" style={{ padding: "6px 8px" }}>{formatDuration(row.clockS)}</td>
                <td style={{ padding: "6px 8px" }}>
                  <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
                    {row.chips.map((chip, i) => (
                      <span
                        key={i}
                        style={{
                          fontSize: 10.5,
                          fontWeight: 600,
                          padding: "1px 7px",
                          borderRadius: 20,
                          color: chip.type === "gel" ? "#f0a02e" : "#3d7fd6",
                          border: `1px solid ${chip.type === "gel" ? "#f0a02e" : "#3d7fd6"}`,
                        }}
                      >
                        {chip.label}
                      </span>
                    ))}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
