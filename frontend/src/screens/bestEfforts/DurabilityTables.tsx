import { useNavigate } from "react-router-dom";
import { SPORT_COLOR } from "./durabilityChart";
import type { DurabilityCell, DurabilitySportSection } from "../../api/types";

const WINDOW_ROWS: { label: string; windowS: number }[] = [
  { label: "5 min", windowS: 300 },
  { label: "20 min", windowS: 1200 },
  { label: "60 min", windowS: 3600 },
];

function fmtThreshold(sport: "ride" | "run", threshold: number): string {
  return sport === "ride" ? `${threshold.toLocaleString()} kJ` : `${threshold} min`;
}

function headline(sport: "ride" | "run", section: DurabilitySportSection): string | null {
  const nonZero = section.thresholds.filter((t) => t > 0);
  for (let i = nonZero.length - 1; i >= 0; i--) {
    const cell = section.cells.find((c) => c.threshold === nonZero[i] && c.window_s === 1200);
    if (cell && cell.pct_of_fresh != null) {
      return `Durability: 20 min after ${fmtThreshold(sport, nonZero[i])} = ${cell.pct_of_fresh}% of fresh`;
    }
  }
  return null;
}

function Cell({ cell, isFreshColumn, sport }: { cell: DurabilityCell | undefined; isFreshColumn: boolean; sport: "ride" | "run" }) {
  const navigate = useNavigate();
  if (!cell) {
    return (
      <div title="No qualifying effort this period" style={{ padding: "8px 0" }}>
        <span className="mono" style={{ fontSize: 14, fontWeight: 700, color: "var(--ink3)" }}>&mdash;</span>
      </div>
    );
  }
  const pctLabel = isFreshColumn ? "fresh" : cell.pct_of_fresh != null ? `${cell.pct_of_fresh}%` : "—";
  const barPct = isFreshColumn ? 100 : (cell.pct_of_fresh ?? 0);
  return (
    <div
      title={`${cell.activity_name} · ${cell.date}`}
      onClick={() => navigate(`/activities/${cell.activity_id}`)}
      style={{ padding: "8px 0", cursor: "pointer" }}
    >
      <span className="mono" style={{ fontSize: 14, fontWeight: 700, color: "var(--ink)" }}>{cell.power} W</span>
      <span className="mono" style={{ fontSize: 11, color: "var(--ink2)", marginLeft: 6 }}>{pctLabel}</span>
      <div style={{ width: "100%", maxWidth: 140, height: 4, borderRadius: 2, background: "var(--line)", marginTop: 5, overflow: "hidden" }}>
        <div style={{ width: `${barPct}%`, height: "100%", background: SPORT_COLOR[sport], borderRadius: 2 }} />
      </div>
    </div>
  );
}

export function DurabilitySportTable({ sport, section }: { sport: "ride" | "run"; section: DurabilitySportSection }) {
  const title =
    sport === "ride" ? "CYCLING · BEST POWER AFTER WORK DONE" : "RUNNING · BEST POWER AFTER TIME RUNNING";
  const cols = section.thresholds;
  const gridCols = `84px repeat(${cols.length}, minmax(96px,1fr))`;
  const headlineText = headline(sport, section);

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "18px 0", overflow: "hidden" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", padding: "0 18px 14px", justifyContent: "space-between" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span style={{ width: 10, height: 10, borderRadius: 3, background: SPORT_COLOR[sport], flexShrink: 0 }} />
          <span className="mono" style={{ fontSize: 11, fontWeight: 700, color: "var(--ink3)" }}>{title}</span>
        </div>
        {headlineText && <span style={{ fontSize: 12.5, color: "var(--ink2)" }}>{headlineText}</span>}
      </div>
      <div style={{ overflowX: "auto" }}>
        <div style={{ minWidth: 84 + cols.length * 108, padding: "0 18px 4px" }}>
          <div className="mono" style={{ display: "grid", gridTemplateColumns: gridCols, columnGap: 12, fontSize: 10, textTransform: "uppercase", letterSpacing: "0.06em", color: "var(--ink3)", paddingBottom: 8 }}>
            <span>Window</span>
            {cols.map((threshold) => (
              <span key={threshold}>{threshold === 0 ? "Fresh" : `After ${fmtThreshold(sport, threshold)}`}</span>
            ))}
          </div>
          {WINDOW_ROWS.map((row) => (
            <div key={row.windowS} style={{ display: "grid", gridTemplateColumns: gridCols, columnGap: 12, alignItems: "center", borderTop: "1px solid var(--line)" }}>
              <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)" }}>{row.label}</span>
              {cols.map((threshold) => (
                <Cell
                  key={threshold}
                  cell={section.cells.find((c) => c.threshold === threshold && c.window_s === row.windowS)}
                  isFreshColumn={threshold === 0}
                  sport={sport}
                />
              ))}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
