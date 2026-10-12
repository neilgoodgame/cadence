import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { getRaceBlock } from "../../api/races";
import type { Race } from "../../api/types";
import { formatDuration } from "../../lib/format";
import { currentWeekIndex } from "../race-detail/raceBlockChart";
import { TrainingBlockChart } from "../race-detail/TrainingBlockChart";

function tile(label: string, value: string, sub?: string) {
  return (
    <div style={{ padding: "10px 14px", borderRadius: 10, background: "var(--canvas)", minWidth: 100 }}>
      <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ink3)" }}>
        {label}
      </div>
      <div className="mono" style={{ fontSize: 18, fontWeight: 700, color: "var(--ink)", marginTop: 2 }}>
        {value}
      </div>
      {sub && <div style={{ fontSize: 11, color: "var(--ink3)", marginTop: 1 }}>{sub}</div>}
    </div>
  );
}

/** The next upcoming A race, with a compact block chart and key tiles - README §4.1. */
export function CurrentBlockCard({ race, tuneUps }: { race: Race; tuneUps: Race[] }) {
  const navigate = useNavigate();
  const { data: block } = useQuery({ queryKey: ["race-block", race.id], queryFn: () => getRaceBlock(race.id) });

  const today = new Date();
  const days = Math.round((new Date(race.date + "T00:00:00").getTime() - today.getTime()) / 86_400_000);
  const weekIndex = block ? currentWeekIndex(block.weeks, today) : -1;
  const weekLabel = block && weekIndex >= 0 ? `week ${weekIndex + 1} of ${block.weeks.length}` : null;

  const week1Ctl = block?.weeks[0]?.ctl ?? null;
  const latestCtl = block && weekIndex >= 0 ? block.weeks[weekIndex]?.ctl ?? null : block?.weeks.at(-1)?.ctl ?? null;
  const ctlChange = week1Ctl != null && latestCtl != null ? latestCtl - week1Ctl : null;

  const distanceSoFar = block
    ? block.weeks.slice(0, weekIndex >= 0 ? weekIndex + 1 : block.weeks.length).reduce((sum, w) => sum + (w.run_km ?? 0) + (w.ride_km ?? 0), 0)
    : null;
  const stillPlanned = block ? block.weeks.filter((w) => w.planned).length : 0;

  return (
    <div
      onClick={() => navigate(`/races/${race.id}`)}
      style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20, cursor: "pointer" }}
    >
      <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ember)" }}>
        Current block &middot; next A race
      </div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginTop: 6, gap: 20 }}>
        <div>
          <div style={{ fontSize: 20, fontWeight: 800, color: "var(--ink)" }}>{race.name}</div>
          <div style={{ fontSize: 12.5, color: "var(--ink3)", marginTop: 2 }}>
            {race.date}
            {race.distance_km != null && ` · ${race.distance_km} km`}
            {race.goal_time && ` · Goal ${race.goal_time}`}
          </div>
          {tuneUps.length > 0 && (
            <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 4 }}>
              Tune-ups: {tuneUps.map((t) => `${t.priority} · ${t.name}`).join(", ")}
            </div>
          )}
        </div>
        <div style={{ textAlign: "right", flexShrink: 0 }}>
          <div className="mono" style={{ fontSize: 40, fontWeight: 800, lineHeight: 1, color: "var(--ember)" }}>
            {Math.max(days, 0)}
          </div>
          <div style={{ fontSize: 11, color: "var(--ink3)", marginTop: 2 }}>
            days to go{weekLabel && ` · ${weekLabel}`}
          </div>
        </div>
      </div>

      {block && (
        <>
          <div style={{ marginTop: 16 }}>
            <TrainingBlockChart
              weeks={block.weeks}
              comparisonWeeks={block.comparison?.weeks ?? null}
              metric="tss"
              height={96}
              currentIndex={weekIndex}
              selectedIndex={null}
              onSelectWeek={() => {}}
              compact
            />
          </div>
          <div style={{ display: "flex", gap: 10, marginTop: 14, flexWrap: "wrap" }}>
            {latestCtl != null && tile("Fitness", latestCtl.toFixed(0), ctlChange != null ? `${ctlChange >= 0 ? "+" : ""}${ctlChange.toFixed(0)} since wk 1` : undefined)}
            {block.race_day_form.tsb != null && tile("Form", block.race_day_form.tsb.toFixed(0))}
            {distanceSoFar != null && tile("Distance", `${distanceSoFar.toFixed(0)} km`, stillPlanned > 0 ? `${stillPlanned} wk planned` : undefined)}
            {block.prediction && tile("Predicted", formatDuration(block.prediction.time_s))}
            {block.comparison && weekIndex >= 0 && block.comparison.weeks[weekIndex]?.ctl != null && (
              tile("vs comparison", `${latestCtl != null ? (latestCtl - (block.comparison.weeks[weekIndex].ctl ?? 0)).toFixed(0) : "–"}`, "same week")
            )}
          </div>
        </>
      )}
    </div>
  );
}
