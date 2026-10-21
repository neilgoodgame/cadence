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

/** For a B/C race's own block there's no backend phase list (block.phases is empty) - the
 * table instead shows a position relative to the race itself ("-2 wk" / "Race" / "+1 wk").
 * A parent-linked B/C's block always ends at the race itself, so only negative offsets (and
 * "Race" at the last row) ever show. */
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

const DAY_MS = 86_400_000;

/** "29 Jun" - en-GB order, matching the prototype (and "Sep" rather than en-GB's "Sept"). */
function shortDate(isoDate: string, extraDays = 0, opts: Intl.DateTimeFormatOptions = {}): string {
  const d = new Date(new Date(isoDate + "T00:00:00Z").getTime() + extraDays * DAY_MS);
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short", timeZone: "UTC", ...opts }).replace("Sept", "Sep");
}

/** Whole-minute "H:MM", e.g. 13300s -> "3:41". */
function hm(seconds: number): string {
  const totalMin = Math.round(seconds / 60);
  return `${Math.floor(totalMin / 60)}:${String(totalMin % 60).padStart(2, "0")}`;
}

/** A planned week with nothing scheduled - RaceBlockWeek's "all null" case. */
function isUnscheduled(week: RaceBlockWeek): boolean {
  return week.planned && week.tss == null;
}

function weekKm(week: RaceBlockWeek): number | null {
  return week.run_km == null && week.ride_km == null ? null : (week.run_km ?? 0) + (week.ride_km ?? 0);
}

function raceNote(r: RaceBlockWeek["races"][number]): string {
  return `${r.priority} · ${r.name}${r.result_s != null ? ` · ${formatDuration(r.result_s)}` : ""}`;
}

function totalsTile(label: string, value: string, sub?: string) {
  return (
    <div key={label} style={{ border: "1px solid var(--line)", borderRadius: 10, padding: "10px 12px" }}>
      <div className="mono" style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ink3)" }}>{label}</div>
      <div className="mono" style={{ fontSize: 16, fontWeight: 600, marginTop: 3 }}>{value}</div>
      {sub && <div style={{ fontSize: 11.5, color: "var(--ink3)", marginTop: 1 }}>{sub}</div>}
    </div>
  );
}

/** Weekly table columns: Wk, Week of, Phase, TSS, then RUN (km, time, runs, pace, long), RIDE
 * (time, rides, avg W), CTL, vs comparison, note - the RUN/RIDE group header spans by index. */
const MONO = '"JetBrains Mono", monospace';
const TABLE_COLS = "28px 60px 48px repeat(11, minmax(44px,1fr)) minmax(100px,1.3fr)";

/** README §4.2.7 "Training block" card - the weekly bar+CTL chart, a detail panel for the
 * hovered/selected week, totals tiles and a hover-synced weekly table (layout per the Races
 * prototype). Totals tiles only make sense for a full A-race block (a B/C's 5-week window is
 * too short to read trend from), so they're gated on `race.priority === "A"`. */
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

  const isA = race.priority === "A";
  const N = block.weeks.length;
  const todayIso = new Date().toISOString().slice(0, 10);
  const inProgress = currentIndex >= 0;
  const notStarted = isA && !inProgress && block.weeks.length > 0 && todayIso < block.weeks[0].week_start;
  const actualWeeks = block.weeks.map((week, index) => ({ week, index })).filter(({ week }) => !week.planned);

  // The week the detail panel describes when nothing's been hovered/clicked: this week if the
  // block is in progress, the race week for a B/C, the peak-load week of a finished A block,
  // else week 1.
  const raceWeekIndex = currentWeekIndex(block.weeks, new Date(race.date + "T00:00:00"));
  const peakLoadIndex = actualWeeks.reduce((best, { week, index }) => ((week.tss ?? 0) > (block.weeks[best]?.tss ?? -1) ? index : best), -1);
  const defaultIndex = inProgress ? currentIndex : !isA && raceWeekIndex >= 0 ? raceWeekIndex : peakLoadIndex >= 0 ? peakLoadIndex : 0;
  const focusIndex = selectedIndex ?? defaultIndex;
  const focusWeek = block.weeks[focusIndex] ?? null;
  const focusComparisonWeek = comparisonWeeks?.[focusIndex] ?? null;

  // Totals tiles are scoped to the race's own sport (run vs ride) - a running race's block
  // mixes in bike cross-training (or vice versa) that would otherwise distort its stats.
  const raceSport: "run" | "ride" = race.sport === "bike" ? "ride" : "run";
  const sportKm = (week: RaceBlockWeek) => (raceSport === "ride" ? week.ride_km : week.run_km);
  const sportMovingS = (week: RaceBlockWeek) => (raceSport === "ride" ? week.ride_moving_s : week.run_moving_s);
  const sportLongKm = (week: RaceBlockWeek) => (raceSport === "ride" ? week.ride_long_km : week.run_long_km);
  const sportSessions = (week: RaceBlockWeek) => (raceSport === "ride" ? week.ride_count : week.run_count);

  // Peak week includes race weeks (sportKm sums every run/ride in the week regardless of race
  // status). Longest session is training-only: sportLongKm already excludes race activities
  // (it backs the weekly table's "Long" column), so the race itself never shows up as the
  // block's longest session.
  const peakWeek = actualWeeks.reduce<{ km: number; index: number } | null>((best, { week, index }) => {
    const km = sportKm(week) ?? 0;
    return best == null || km > best.km ? { km, index } : best;
  }, null);
  const longest = actualWeeks.reduce<{ km: number; index: number } | null>((best, { week, index }) => {
    const km = sportLongKm(week) ?? 0;
    return best == null || km > best.km ? { km, index } : best;
  }, null);
  const totalKm = actualWeeks.reduce((sum, { week }) => sum + (sportKm(week) ?? 0), 0);
  const totalHours = actualWeeks.reduce((sum, { week }) => sum + (sportMovingS(week) ?? 0) / 3600, 0);
  const totalSessions = actualWeeks.reduce((sum, { week }) => sum + (sportSessions(week) ?? 0), 0);
  const week1Ctl = block.weeks[0]?.ctl ?? null;
  const latestCtl = actualWeeks.at(-1)?.week.ctl ?? null;
  const ctlChange = week1Ctl != null && latestCtl != null ? latestCtl - week1Ctl : null;
  const plannedWeeks = block.weeks.filter((w) => w.planned && !isUnscheduled(w));
  const span = inProgress ? "so far" : "over the block";
  const averages = weeklyAverages(actualWeeks.filter(({ index }) => index !== currentIndex).map(({ week }) => week));

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 10 }}>
        <div>
          <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>
            {race.priority === "A"
              ? `Training block · ${block.weeks.length} weeks`
              : block.parent
                ? `Leading into the race · ${block.weeks.length} weeks`
                : `Around the race · ${block.weeks.length} weeks`}
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

      <div style={{ display: "flex", gap: 14, flexWrap: "wrap", fontSize: 11.5, color: "var(--ink3)", marginTop: 14 }}>
        {(sport === "all" ? (["run", "ride", "other"] as const) : [sport]).map((s) =>
          legendItem(SEGMENT_LABEL[s], { background: SEGMENT_COLOR[s] }),
        )}
        {metric === "tss" && sport === "all" && legendItem("Race", { background: "repeating-linear-gradient(45deg, var(--ink) 0 2px, transparent 2px 4px)", opacity: 0.7 })}
        {legendItem("Planned", { border: "1px dashed var(--ink2)" })}
        <span style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
          <span style={{ width: 16, height: 2, background: "var(--ink)", display: "inline-block" }} />
          Fitness (CTL)
        </span>
        {comparisonWeeks && (
          <span style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, border: "1px dashed var(--ink3)", display: "inline-block" }} />
            <span style={{ width: 16, borderTop: "2px dashed var(--ink3)", display: "inline-block" }} />
            {comparisonRace?.name ?? "Comparison"} {comparisonYear} block
          </span>
        )}
      </div>

      {notStarted && (
        <div style={{ fontSize: 12.5, color: "var(--ink2)", background: "var(--elev)", border: "1px dashed var(--line)", borderRadius: 10, padding: "10px 12px", marginTop: 14 }}>
          This block starts {shortDate(block.weeks[0].week_start, 0, { weekday: "short" })}. Weeks with scheduled workouts show as
          planned; the rest fill in as you schedule them.
        </div>
      )}

      <div style={{ marginTop: 14 }}>
        <TrainingBlockChart
          weeks={block.weeks}
          comparisonWeeks={comparisonWeeks}
          metric={metric}
          sport={sport}
          height={210}
          currentIndex={currentIndex}
          selectedIndex={focusIndex}
          onSelectWeek={onSelectIndex}
          phases={block.phases}
        />
      </div>

      {focusWeek && (
        <div style={{ background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 10, padding: "10px 14px", display: "flex", flexDirection: "column", gap: 3, marginTop: 12 }}>
          <div style={{ fontSize: 13, fontWeight: 700 }}>
            {isA ? `Week ${focusIndex + 1} of ${N}` : phaseLabel(block, race, focusIndex) === "Race" ? "Race week" : phaseLabel(block, race, focusIndex)}
            {` · ${shortDate(focusWeek.week_start)}–${shortDate(focusWeek.week_start, 6)}`}
            {focusIndex === currentIndex ? " · this week" : focusWeek.planned ? " · planned" : ""}
          </div>
          <div className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>
            {isUnscheduled(focusWeek)
              ? "Nothing scheduled yet"
              : [
                  focusWeek.tss != null && `${focusWeek.tss} TSS`,
                  weekKm(focusWeek) != null && `${weekKm(focusWeek)!.toFixed(1)} km`,
                  focusWeek.hours != null && `${hm(focusWeek.hours * 3600)} h`,
                  focusWeek.sessions != null && `${focusWeek.sessions} sessions`,
                  (race.sport === "bike" ? focusWeek.ride_long_km : focusWeek.run_long_km) != null &&
                    `long ${(race.sport === "bike" ? focusWeek.ride_long_km : focusWeek.run_long_km)!.toFixed(1)} km`,
                  focusWeek.ctl != null && `CTL ${Math.round(focusWeek.ctl)}`,
                ]
                  .filter(Boolean)
                  .join(" · ")}
          </div>
          {sport === "all" && !isUnscheduled(focusWeek) && (
            <div className="mono" style={{ fontSize: 12, display: "flex", gap: 12 }}>
              {(["run", "ride"] as const).map((s) => (
                <span key={s} style={{ color: SEGMENT_COLOR[s] }}>
                  {SEGMENT_LABEL[s]}: {formatMetric(metricValue(focusWeek, metric, s), metric)} {metricAxisLabel(metric)}
                </span>
              ))}
            </div>
          )}
          {sport === "all" && !isUnscheduled(focusWeek) && (
            <div className="mono" style={{ fontSize: 12, display: "flex", gap: 14, flexWrap: "wrap" }}>
              {(["run", "ride"] as const)
                .filter((s) => ((s === "run" ? focusWeek.run_count : focusWeek.ride_count) ?? 0) > 0)
                .map((s) => {
                  const km = s === "run" ? focusWeek.run_km : focusWeek.ride_km;
                  const movingS = s === "run" ? focusWeek.run_moving_s : focusWeek.ride_moving_s;
                  const count = (s === "run" ? focusWeek.run_count : focusWeek.ride_count)!;
                  return (
                    <span key={s} style={{ color: SEGMENT_COLOR[s] }}>
                      {SEGMENT_LABEL[s]}: {km != null ? `${km.toFixed(1)} km` : "–"} ·{" "}
                      {movingS != null ? `${hm(movingS)} h` : "–"} · {count} session{count === 1 ? "" : "s"}
                    </span>
                  );
                })}
            </div>
          )}
          {focusWeek.races.length > 0 && (
            <div style={{ fontSize: 12, fontWeight: 600 }}>⚑ {focusWeek.races.map((r) => raceNote(r).replace(`${r.priority} ·`, `${r.priority} race ·`)).join(", ")}</div>
          )}
          {focusComparisonWeek && (
            <div className="mono" style={{ fontSize: 11.5, color: "var(--ink3)" }}>
              {comparisonRace?.name ?? "Comparison"} {comparisonYear}, week {focusIndex + 1}:{" "}
              {isUnscheduled(focusComparisonWeek) || focusComparisonWeek.tss == null
                ? "no data"
                : [
                    `${focusComparisonWeek.tss} TSS`,
                    weekKm(focusComparisonWeek) != null && `${weekKm(focusComparisonWeek)!.toFixed(1)} km`,
                    focusComparisonWeek.ctl != null && `CTL ${Math.round(focusComparisonWeek.ctl)}`,
                  ]
                    .filter(Boolean)
                    .join(" · ")}
            </div>
          )}
        </div>
      )}

      {isA && actualWeeks.length > 0 && (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(140px, 1fr))", gap: 10, marginTop: 14 }}>
          {totalsTile("Distance", `${Math.round(totalKm).toLocaleString("en-GB")} km`, span)}
          {totalsTile("Time", `${hm(totalHours * 3600)} h`, `${totalSessions} sessions`)}
          {peakWeek && totalsTile("Peak week", `${Math.round(peakWeek.km)} km`, `week ${peakWeek.index + 1}`)}
          {longest && longest.km > 0 && totalsTile("Longest session", `${longest.km.toFixed(1)} km`, `week ${longest.index + 1}`)}
          {week1Ctl != null && latestCtl != null && ctlChange != null &&
            totalsTile("Fitness (CTL)", `${Math.round(week1Ctl)} → ${Math.round(latestCtl)}`, `${ctlChange >= 0 ? "+" : ""}${Math.round(ctlChange)} ${span}`)}
          {inProgress && plannedWeeks.length > 0 &&
            totalsTile(
              "Still planned",
              `${plannedWeeks.length} ${plannedWeeks.length === 1 ? "week" : "weeks"}`,
              `${plannedWeeks.reduce((sum, w) => sum + (w.tss ?? 0), 0).toLocaleString("en-GB")} TSS scheduled`,
            )}
        </div>
      )}

      <div style={{ border: "1px solid var(--line)", borderRadius: 10, overflowX: "auto", overflowY: "hidden", marginTop: 14 }}>
        <div style={{ minWidth: 910 }}>
          <div className="mono" style={{ display: "grid", gridTemplateColumns: TABLE_COLS, gap: 10, padding: "8px 14px 0", fontSize: 10, fontWeight: 700, letterSpacing: "0.08em", color: "var(--ink2)" }}>
            <div style={{ gridColumn: "5 / 10", borderBottom: `2px solid ${SEGMENT_COLOR.run}`, paddingBottom: 3, textAlign: "center" }}>RUN</div>
            <div style={{ gridColumn: "10 / 13", borderBottom: `2px solid ${SEGMENT_COLOR.ride}`, paddingBottom: 3, textAlign: "center" }}>RIDE</div>
          </div>
          <div className="mono" style={{ display: "grid", gridTemplateColumns: TABLE_COLS, gap: 10, padding: "8px 14px", fontSize: 10, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ink3)" }}>
            <div>Wk</div>
            <div>Week of</div>
            <div>Phase</div>
            {["TSS", "km", "Time", "Runs", "Pace", "Long", "Time", "Rides", "Avg W", "CTL"].map((h, i) => (
              <div key={i} style={{ textAlign: "right" }} title={h === "Pace" ? "Average pace, min/km" : undefined}>{h}</div>
            ))}
            <div style={{ textAlign: "right" }} title={`${metricAxisLabel(metric)} difference vs the comparison block, same week`}>
              {!isA ? "" : comparisonYear ? `vs ${comparisonYear}` : "vs prev"}
            </div>
            <div />
          </div>
          {block.weeks.map((week, i) => {
            const comparisonWeek = comparisonWeeks?.[i] ?? null;
            const none = isUnscheduled(week);
            const thisValue = metricValue(week, metric, sport);
            const compValue = comparisonWeek ? metricValue(comparisonWeek, metric, sport) : null;
            const delta = none || thisValue == null || compValue == null ? null : thisValue - compValue;
            const isCurrent = i === currentIndex;
            const isSelected = i === focusIndex;
            const ink = none ? "var(--ink3)" : week.planned ? "var(--ink2)" : "var(--ink)";
            const num = (bold = false): React.CSSProperties => ({ fontFamily: MONO, textAlign: "right", fontSize: 12.5, color: ink, fontWeight: bold ? 700 : 500 });
            const dash = "—";
            const note = none
              ? "Not scheduled"
              : week.races.length > 0
                ? `⚑ ${week.races.map(raceNote).join(", ")}`
                : week.planned
                  ? "Planned"
                  : isCurrent
                    ? "This week"
                    : "";
            return (
              <div
                key={i}
                onMouseEnter={() => onSelectIndex(i)}
                style={{
                  display: "grid",
                  gridTemplateColumns: TABLE_COLS,
                  gap: 10,
                  padding: "8px 14px",
                  alignItems: "center",
                  borderTop: "1px solid var(--line)",
                  cursor: "pointer",
                  background: isSelected ? "var(--elev)" : "transparent",
                  boxShadow: isCurrent ? "inset 3px 0 0 var(--ember)" : "none",
                  fontStyle: week.planned ? "italic" : "normal",
                }}
              >
                <div style={{ fontFamily: MONO, fontSize: 12.5, fontWeight: 700, color: isCurrent ? "var(--ember)" : ink }}>{i + 1}</div>
                <div style={{ fontFamily: MONO, fontSize: 12, color: "var(--ink2)" }}>{shortDate(week.week_start)}</div>
                <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink3)" }}>{phaseLabel(block, race, i)}</div>
                <div style={num(true)}>{week.tss ?? dash}</div>
                <div style={num()}>{week.run_km?.toFixed(1) ?? dash}</div>
                <div style={num()}>{week.run_moving_s != null && !none ? hm(week.run_moving_s) : dash}</div>
                <div style={num()}>{week.run_count ?? dash}</div>
                <div style={num()}>{week.run_avg_pace_s_per_km != null ? formatPace(week.run_avg_pace_s_per_km).replace(" /km", "") : dash}</div>
                <div style={num()}>{week.run_long_km?.toFixed(1) ?? dash}</div>
                <div style={num()}>{week.ride_moving_s ? hm(week.ride_moving_s) : dash}</div>
                <div style={num()}>{week.ride_count || dash}</div>
                <div style={num()}>{week.ride_avg_power_w != null ? `${week.ride_avg_power_w} W` : dash}</div>
                <div style={num()}>{week.ctl != null && !none ? Math.round(week.ctl) : dash}</div>
                <div style={{ ...num(), fontWeight: 600, color: delta == null ? "var(--ink3)" : delta >= 0 ? "#2fa66a" : "#e0442e" }}>
                  {delta == null ? (isA ? dash : "") : `${delta >= 0 ? "+" : "−"}${formatMetric(Math.abs(delta), metric)}`}
                </div>
                <div
                  style={{
                    fontSize: 12,
                    fontWeight: !none && week.races.length > 0 ? 600 : 500,
                    color: !none && week.races.length > 0 ? "var(--ink)" : "var(--ink3)",
                    whiteSpace: "nowrap",
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    fontStyle: "normal",
                  }}
                  title={note}
                >
                  {note}
                </div>
              </div>
            );
          })}
          {averages && (
            <div
              className="mono"
              title={`Weekly average over ${averages.weeks} completed weeks`}
              style={{ display: "grid", gridTemplateColumns: TABLE_COLS, gap: 10, padding: "8px 14px", alignItems: "center", borderTop: "2px solid var(--line)", fontSize: 12.5, fontWeight: 600, color: "var(--ink2)" }}
            >
              <div style={{ gridColumn: "1 / 4", fontSize: 10, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ink3)" }}>
                Avg / week
              </div>
              {[
                averages.tss != null ? Math.round(averages.tss) : null,
                averages.runKm?.toFixed(1),
                averages.runMovingS != null ? hm(averages.runMovingS) : null,
                averages.runCount?.toFixed(1),
                averages.runPace != null ? formatPace(averages.runPace).replace(" /km", "") : null,
                averages.runLongKm?.toFixed(1),
                averages.rideMovingS ? hm(averages.rideMovingS) : null,
                averages.rideCount ? averages.rideCount.toFixed(1) : null,
                averages.ridePowerW != null ? `${Math.round(averages.ridePowerW)} W` : null,
                averages.ctl != null ? Math.round(averages.ctl) : null,
              ].map((v, i) => (
                <div key={i} style={{ textAlign: "right" }}>{v ?? "—"}</div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function mean(values: (number | null)[]): number | null {
  const present = values.filter((v): v is number => v != null);
  return present.length > 0 ? present.reduce((a, b) => a + b, 0) / present.length : null;
}

/** Per-week averages for the weekly table's footer row, over completed weeks only (planned and
 * the in-progress week would drag them down). Volume columns treat a missing value as zero -
 * a week with no runs is a 0 km week - while pace and power are time-weighted across the weeks
 * that have them, and "Long" averages only the weeks that had a long session. */
function weeklyAverages(weeks: RaceBlockWeek[]) {
  if (weeks.length < 2) return null;
  const sum = (f: (w: RaceBlockWeek) => number | null) => weeks.reduce((acc, w) => acc + (f(w) ?? 0), 0);
  const avg = (f: (w: RaceBlockWeek) => number | null) => sum(f) / weeks.length;
  const pacedWeeks = weeks.filter((w) => w.run_avg_pace_s_per_km != null && w.run_km);
  const pacedKm = pacedWeeks.reduce((acc, w) => acc + w.run_km!, 0);
  const poweredWeeks = weeks.filter((w) => w.ride_avg_power_w != null && w.ride_moving_s);
  const poweredS = poweredWeeks.reduce((acc, w) => acc + w.ride_moving_s!, 0);
  return {
    weeks: weeks.length,
    tss: avg((w) => w.tss),
    runKm: avg((w) => w.run_km),
    runMovingS: avg((w) => w.run_moving_s),
    runCount: avg((w) => w.run_count),
    runPace: pacedKm > 0 ? pacedWeeks.reduce((acc, w) => acc + w.run_avg_pace_s_per_km! * w.run_km!, 0) / pacedKm : null,
    runLongKm: mean(weeks.map((w) => w.run_long_km)),
    rideMovingS: avg((w) => w.ride_moving_s),
    rideCount: avg((w) => w.ride_count),
    ridePowerW: poweredS > 0 ? poweredWeeks.reduce((acc, w) => acc + w.ride_avg_power_w! * w.ride_moving_s!, 0) / poweredS : null,
    ctl: mean(weeks.map((w) => w.ctl)),
  };
}
