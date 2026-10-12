import { useState } from "react";
import type { Race, RaceBlock, RaceBlockWeek } from "../../api/types";
import { formatDuration, formatPace } from "../../lib/format";
import {
  SEGMENT_COLOR,
  SEGMENT_LABEL,
  currentWeekIndex,
  metricAxisLabel,
  metricValue,
  type BlockChartMetric,
  type BlockChartSport,
} from "./raceBlockChart";
import { TrainingBlockChart } from "./TrainingBlockChart";

const METRICS: { key: BlockChartMetric; label: string }[] = [
  { key: "tss", label: "Load (TSS)" },
  { key: "distance", label: "Distance" },
  { key: "time", label: "Time" },
];

const SPORTS: { key: BlockChartSport; label: string }[] = [
  { key: "all", label: "All" },
  { key: "run", label: "Run" },
  { key: "ride", label: "Ride" },
];

function legendItem(label: string, swatch: React.CSSProperties) {
  return (
    <span key={label} style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
      <span style={{ width: 10, height: 10, borderRadius: 2, display: "inline-block", ...swatch }} />
      {label}
    </span>
  );
}

function formatMetric(value: number | null, metric: BlockChartMetric): string {
  return value?.toFixed(metric === "tss" ? 0 : 1) ?? "–";
}

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

/** For a B/C race's own 5-week block there's no backend phase list (block.phases is empty) -
 * the table instead shows a position relative to the race itself ("-2 wk" / "Race" / "+1 wk"). */
function relativeWeekLabel(weeks: RaceBlockWeek[], raceDate: string, index: number): string {
  const raceIndex = currentWeekIndex(weeks, new Date(raceDate + "T00:00:00"));
  if (raceIndex === -1) return "";
  const diff = index - raceIndex;
  if (diff === 0) return "Race";
  return diff < 0 ? `${diff} wk` : `+${diff} wk`;
}

function phaseLabel(block: RaceBlock, race: Race, index: number): string {
  if (block.phases.length > 0) {
    const phase = block.phases.find((p) => index + 1 >= p.start_week && index + 1 <= p.end_week);
    return phase?.name ?? "";
  }
  return relativeWeekLabel(block.weeks, race.date, index);
}

function totalsTile(label: string, value: string, sub?: string) {
  return (
    <div style={{ padding: "10px 14px", borderRadius: 10, background: "var(--canvas)", minWidth: 110 }}>
      <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ink3)" }}>{label}</div>
      <div className="mono" style={{ fontSize: 17, fontWeight: 700, marginTop: 2 }}>{value}</div>
      {sub && <div style={{ fontSize: 11, color: "var(--ink3)", marginTop: 1 }}>{sub}</div>}
    </div>
  );
}

/** README §4.2.7 "Training block" card - the weekly bar+CTL chart plus a hover-synced
 * weekly table. Totals tiles only make sense for a full A-race block (a B/C's 5-week window
 * is too short to read trend from), so they're gated on `race.priority === "A"`. */
export function TrainingBlockCard({
  race,
  block,
  allRaces,
  selectedIndex,
  onSelectIndex,
}: {
  race: Race;
  block: RaceBlock;
  allRaces: Race[];
  selectedIndex: number | null;
  onSelectIndex: (index: number | null) => void;
}) {
  const [metric, setMetric] = useState<BlockChartMetric>("tss");
  const [sport, setSport] = useState<BlockChartSport>("all");
  const [compareOn, setCompareOn] = useState(block.comparison != null);

  const currentIndex = currentWeekIndex(block.weeks, new Date());
  const comparisonWeeks = compareOn ? block.comparison?.weeks ?? null : null;
  const comparisonRace = block.comparison ? allRaces.find((r) => r.id === block.comparison!.race_id) ?? null : null;
  const comparisonYear = comparisonWeeks?.[0] ? new Date(comparisonWeeks[0].week_start + "T00:00:00").getFullYear() : null;
  const truncatedByRace = block.truncated_by_race_id ? allRaces.find((r) => r.id === block.truncated_by_race_id) ?? null : null;

  const hoveredIndex = selectedIndex ?? currentIndex;
  const hoveredWeek = hoveredIndex >= 0 ? block.weeks[hoveredIndex] : null;
  const hoveredComparisonWeek = comparisonWeeks && hoveredIndex >= 0 ? comparisonWeeks[hoveredIndex] ?? null : null;

  // Peak week / longest session "excluding races" - the API doesn't break a week's totals
  // into race vs training portions, so this approximates by excluding weeks that contain a
  // race entirely rather than subtracting the race's own contribution out of a race week.
  const nonRaceWeeks = block.weeks.filter((w) => w.races.length === 0);
  const peakWeekKm = nonRaceWeeks.length > 0 ? Math.max(...nonRaceWeeks.map((w) => (w.run_km ?? 0) + (w.ride_km ?? 0))) : null;
  const longestKm = nonRaceWeeks.length > 0 ? Math.max(...nonRaceWeeks.map((w) => Math.max(w.run_long_km ?? 0, w.ride_long_km ?? 0))) : null;
  const totalKm = block.weeks.reduce((sum, w) => sum + (w.run_km ?? 0) + (w.ride_km ?? 0), 0);
  const totalHours = block.weeks.reduce((sum, w) => sum + (w.hours ?? 0), 0);
  const totalSessions = block.weeks.reduce((sum, w) => sum + (w.sessions ?? 0), 0);
  const week1Ctl = block.weeks[0]?.ctl ?? null;
  const latestCtl = currentIndex >= 0 ? block.weeks[currentIndex]?.ctl ?? null : block.weeks.at(-1)?.ctl ?? null;
  const ctlChange = week1Ctl != null && latestCtl != null ? latestCtl - week1Ctl : null;
  const stillPlanned = block.weeks.filter((w) => w.planned).length;

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 10 }}>
        <div>
          <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>
            {race.priority === "A" ? `Training block · ${block.weeks.length} weeks` : "Around the race · 5 weeks"}
          </h2>
          <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>
            {block.start} – {block.end}
            {truncatedByRace && ` · starts the week after ${truncatedByRace.name}`}
          </div>
        </div>
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
          {block.comparison && (
            <button
              onClick={() => setCompareOn((v) => !v)}
              style={{
                border: `1px solid ${compareOn ? "var(--ember)" : "var(--line)"}`,
                background: compareOn ? "var(--ember)22" : "none",
                color: compareOn ? "var(--ember)" : "var(--ink2)",
                borderRadius: 20,
                fontSize: 12,
                fontWeight: 600,
                padding: "5px 12px",
                cursor: "pointer",
              }}
            >
              {compareOn ? "✓" : ""} Compare: {comparisonRace?.name ?? "previous"} {comparisonYear}
            </button>
          )}
          <div style={{ display: "flex", gap: 2, background: "var(--canvas)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
            {SPORTS.map((s) => (
              <div key={s.key} onClick={() => setSport(s.key)} style={segStyle(s.key === sport)}>{s.label}</div>
            ))}
          </div>
          <div style={{ display: "flex", gap: 2, background: "var(--canvas)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
            {METRICS.map((m) => (
              <div key={m.key} onClick={() => setMetric(m.key)} style={segStyle(m.key === metric)}>{m.label}</div>
            ))}
          </div>
        </div>
      </div>

      <div style={{ marginTop: 18 }}>
        <TrainingBlockChart
          weeks={block.weeks}
          comparisonWeeks={comparisonWeeks}
          metric={metric}
          sport={sport}
          height={210}
          currentIndex={currentIndex}
          selectedIndex={selectedIndex}
          onSelectWeek={onSelectIndex}
        />
      </div>

      <div style={{ display: "flex", gap: 14, flexWrap: "wrap", fontSize: 11.5, color: "var(--ink3)", marginTop: 8 }}>
        {(sport === "all" ? (["run", "ride", "other"] as const) : [sport]).map((s) =>
          legendItem(SEGMENT_LABEL[s], { background: SEGMENT_COLOR[s] }),
        )}
        {metric === "tss" && sport === "all" && legendItem("Race", { background: "repeating-linear-gradient(45deg, var(--ink) 0 2px, transparent 2px 4px)", opacity: 0.7 })}
        {legendItem("Planned", { border: "1px dashed var(--ink2)" })}
        {comparisonWeeks && legendItem(`${comparisonYear ?? "Comparison"}`, { border: "1px dashed var(--ink3)" })}
      </div>

      {hoveredWeek && (
        <div style={{ display: "flex", gap: 18, flexWrap: "wrap", fontSize: 12.5, color: "var(--ink2)", marginTop: 10, padding: "8px 12px", background: "var(--canvas)", borderRadius: 8 }}>
          <span className="mono" style={{ fontWeight: 700 }}>{hoveredWeek.week_start}</span>
          <span>{metricAxisLabel(metric)}: {formatMetric(metricValue(hoveredWeek, metric, sport), metric)}</span>
          {sport === "all" && (["run", "ride"] as const).map((s) => (
            <span key={s} style={{ color: SEGMENT_COLOR[s] }}>{SEGMENT_LABEL[s]}: {formatMetric(metricValue(hoveredWeek, metric, s), metric)}</span>
          ))}
          {hoveredWeek.races.map((r) => <span key={r.id} style={{ color: "var(--ember)" }}>⚑ {r.name}</span>)}
          {hoveredComparisonWeek && <span style={{ color: "var(--ink3)" }}>vs {metricAxisLabel(metric)}: {formatMetric(metricValue(hoveredComparisonWeek, metric, sport), metric)}</span>}
        </div>
      )}

      {race.priority === "A" && (
        <div style={{ display: "flex", gap: 10, marginTop: 16, flexWrap: "wrap" }}>
          {totalsTile("Distance", `${totalKm.toFixed(0)} km`)}
          {totalsTile("Time", formatDuration(totalHours * 3600), `${totalSessions} sessions`)}
          {peakWeekKm != null && totalsTile("Peak week", `${peakWeekKm.toFixed(0)} km`)}
          {longestKm != null && totalsTile("Longest session", `${longestKm.toFixed(1)} km`)}
          {ctlChange != null && totalsTile("Fitness change", `${ctlChange >= 0 ? "+" : ""}${ctlChange.toFixed(0)}`)}
          {totalsTile("Still planned", `${stillPlanned} wk`)}
        </div>
      )}

      <div style={{ maxHeight: 360, overflowY: "auto", marginTop: 18 }}>
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12 }}>
          <thead>
            <tr style={{ position: "sticky", top: 0, background: "var(--card)", textAlign: "left" }}>
              {["Wk", "Week of", "Phase", "TSS", "Run km", "Run time", "Runs", "Pace", "Long", "Ride time", "Rides", "Avg W", "CTL", comparisonYear ? `vs ${comparisonYear}` : "vs comp."].map((h) => (
                <th key={h} style={{ fontSize: 9.5, fontWeight: 700, letterSpacing: "0.04em", textTransform: "uppercase", color: "var(--ink3)", padding: "5px 7px", borderBottom: "1px solid var(--line)", whiteSpace: "nowrap" }}>
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {block.weeks.map((week, i) => {
              const comparisonWeek = comparisonWeeks?.[i] ?? null;
              const delta = comparisonWeek ? (metricValue(week, metric, sport) ?? 0) - (metricValue(comparisonWeek, metric, sport) ?? 0) : null;
              const isCurrent = i === currentIndex;
              const isSelected = i === selectedIndex;
              return (
                <tr
                  key={i}
                  onMouseEnter={() => onSelectIndex(i)}
                  style={{
                    cursor: "pointer",
                    fontStyle: week.planned ? "italic" : "normal",
                    background: isSelected ? "var(--canvas)" : "transparent",
                    boxShadow: isCurrent ? "inset 3px 0 0 var(--ember)" : undefined,
                  }}
                >
                  <td className="mono" style={{ padding: "5px 7px" }}>{i + 1}</td>
                  <td className="mono" style={{ padding: "5px 7px", whiteSpace: "nowrap" }}>{week.week_start}</td>
                  <td style={{ padding: "5px 7px", whiteSpace: "nowrap" }}>{phaseLabel(block, race, i)}</td>
                  <td className="mono" style={{ padding: "5px 7px" }}>{week.tss?.toFixed(0) ?? "–"}</td>
                  <td className="mono" style={{ padding: "5px 7px" }}>{week.run_km?.toFixed(0) ?? "–"}</td>
                  <td className="mono" style={{ padding: "5px 7px" }}>{week.run_moving_s != null ? formatDuration(week.run_moving_s) : "–"}</td>
                  <td className="mono" style={{ padding: "5px 7px" }}>{week.run_count ?? "–"}</td>
                  <td className="mono" style={{ padding: "5px 7px" }}>{week.run_avg_pace_s_per_km != null ? formatPace(week.run_avg_pace_s_per_km) : "–"}</td>
                  <td className="mono" style={{ padding: "5px 7px" }}>{week.run_long_km?.toFixed(1) ?? week.ride_long_km?.toFixed(0) ?? "–"}</td>
                  <td className="mono" style={{ padding: "5px 7px" }}>{week.ride_moving_s != null ? formatDuration(week.ride_moving_s) : "–"}</td>
                  <td className="mono" style={{ padding: "5px 7px" }}>{week.ride_count ?? "–"}</td>
                  <td className="mono" style={{ padding: "5px 7px" }}>{week.ride_avg_power_w ?? "–"}</td>
                  <td className="mono" style={{ padding: "5px 7px" }}>{week.ctl?.toFixed(0) ?? "–"}</td>
                  <td className="mono" style={{ padding: "5px 7px", color: delta == null ? "var(--ink3)" : delta >= 0 ? "#2fa66a" : "#e0442e" }}>
                    {delta != null ? `${delta >= 0 ? "+" : ""}${delta.toFixed(0)}` : "–"}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
