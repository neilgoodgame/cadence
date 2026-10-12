import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getDurability } from "../../api/athletes";
import type { RaceBlock } from "../../api/types";

const MAX_PCT = 12;

function bandColor(pct: number): string {
  if (pct < 5) return "#2fa66a";
  if (pct <= 10) return "#f0a02e";
  return "#e0442e";
}

function fmtThreshold(sport: "ride" | "run", threshold: number): string {
  if (threshold === 0) return "Fresh";
  return sport === "ride" ? `After ${(threshold / 1000).toFixed(0)}k kJ` : `After ${threshold} min`;
}

/** README §4.2.8 "Durability and aerobic decoupling" card - A races, run or bike, with 3+
 * weeks of training. The right side reuses block.durability (best power after fatigue,
 * already scoped server-side); the left side re-derives per-session decoupling dots from the
 * athlete's general durability feed, since RaceBlockWeek itself carries no decoupling field. */
export function DurabilityCard({
  athleteId,
  block,
}: {
  athleteId: string;
  block: RaceBlock;
}) {
  const durability = block.durability;
  const { data } = useQuery({
    queryKey: ["durability", athleteId, durability?.sport ?? "all", "all"],
    queryFn: () => getDurability(athleteId, durability?.sport ?? "all", "all"),
    enabled: !!durability,
  });

  if (!durability) return null;

  const startMs = new Date(block.start + "T00:00:00").getTime();
  const endMs = new Date(block.end + "T00:00:00").getTime();
  const sessions = (data?.sessions ?? [])
    .filter((s) => s.sport === durability.sport)
    .filter((s) => {
      const t = new Date(s.date).getTime();
      return t >= startMs && t <= endMs;
    })
    .sort((a, b) => a.date.localeCompare(b.date));

  const latest = sessions.at(-1)?.decoupling_pct ?? null;
  const first = sessions[0]?.decoupling_pct ?? null;
  const change = latest != null && first != null ? latest - first : null;

  const windowCells = durability.current.cells.filter((c) => c.window_s === 1200);
  const comparisonCells = durability.comparison?.cells.filter((c) => c.window_s === 1200) ?? [];

  const summary =
    latest != null
      ? `Decoupling is ${latest.toFixed(1)}% latest${change != null ? `, ${change <= 0 ? "down" : "up"} ${Math.abs(change).toFixed(1)}pt since week 1` : ""}.`
      : "Not enough steady sessions in this block yet.";

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
      <h2 style={{ fontSize: 15, fontWeight: 700, margin: "0 0 14px" }}>Durability and aerobic decoupling</h2>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 24 }}>
        <div>
          <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ink3)", marginBottom: 8 }}>
            Decoupling
          </div>
          <svg width="100%" height={90} viewBox="0 0 400 90">
            <line x1={0} y1={90 - (5 / MAX_PCT) * 80} x2={400} y2={90 - (5 / MAX_PCT) * 80} stroke="var(--line)" strokeDasharray="2 3" />
            <line x1={0} y1={90 - (10 / MAX_PCT) * 80} x2={400} y2={90 - (10 / MAX_PCT) * 80} stroke="var(--line)" strokeDasharray="2 3" />
            {sessions.map((s, i) => {
              const x = sessions.length > 1 ? (i / (sessions.length - 1)) * 400 : 200;
              const y = 90 - (Math.min(MAX_PCT, s.decoupling_pct) / MAX_PCT) * 80;
              return <circle key={s.activity_id} cx={x} cy={y} r={4} fill={bandColor(s.decoupling_pct)} />;
            })}
          </svg>
          <div style={{ fontSize: 12.5, color: "var(--ink2)", marginTop: 6 }}>
            {latest != null ? `${latest.toFixed(1)}%` : "–"}
            {change != null && ` (${change <= 0 ? "-" : "+"}${Math.abs(change).toFixed(1)}pt since week 1)`}
          </div>
        </div>

        <div>
          <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ink3)", marginBottom: 8 }}>
            Best 20-min power after fatigue
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            {windowCells.map((cell) => {
              const pct = cell.threshold === 0 ? 100 : cell.pct_of_fresh ?? 0;
              const comparisonPct = comparisonCells.find((c) => c.threshold === cell.threshold)?.pct_of_fresh ?? null;
              return (
                <div key={cell.threshold}>
                  <div style={{ fontSize: 12, color: "var(--ink2)" }}>
                    {fmtThreshold(durability.sport, cell.threshold)} · <span className="mono">{cell.power} W</span>
                  </div>
                  <div style={{ position: "relative", width: "100%", height: 6, borderRadius: 3, background: "var(--canvas)", marginTop: 3 }}>
                    <div style={{ width: `${pct}%`, height: "100%", background: "var(--ember)", borderRadius: 3 }} />
                    {comparisonPct != null && (
                      <div style={{ position: "absolute", left: `${comparisonPct}%`, top: -2, bottom: -2, width: 2, background: "var(--ink2)" }} />
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 16, flexWrap: "wrap", gap: 8 }}>
        <span style={{ fontSize: 12.5, color: "var(--ink3)" }}>{summary}</span>
        <Link to="/best-efforts?view=durability" style={{ fontSize: 12.5, fontWeight: 600, color: "var(--ember)" }}>
          Durability trends →
        </Link>
      </div>
    </div>
  );
}
