import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { getDurability } from "../../api/athletes";
import { SegmentedControl } from "../../components/SegmentedControl";
import { DurabilityTrendChart } from "./DurabilityTrendChart";
import { DurabilitySportTable } from "./DurabilityTables";
import { HOT_RING_COLOR, SPORT_COLOR, type DurabilityMetric, type DurabilitySportFilter } from "./durabilityChart";
import type { DurabilitySummary } from "../../api/types";

const METRICS: { key: DurabilityMetric; label: string }[] = [
  { key: "dec", label: "Decoupling" },
  { key: "ef", label: "Efficiency" },
];
const SPORTS: { key: DurabilitySportFilter; label: string }[] = [
  { key: "all", label: "All" },
  { key: "ride", label: "Ride" },
  { key: "run", label: "Run" },
];

function summaryLine(metric: DurabilityMetric, n: number, summary: DurabilitySummary): string {
  const sessionsLabel = `${n} steady session${n === 1 ? "" : "s"}`;
  if (metric === "dec") {
    if (summary.last28_avg == null) return sessionsLabel;
    const improving = summary.prev28_avg != null && summary.last28_avg < summary.prev28_avg;
    const trend = summary.prev28_avg != null ? ` (${improving ? "improving" : "falling"} vs ${summary.prev28_avg.toFixed(1)}%)` : "";
    const cool = summary.last28_cool_avg != null ? ` · cool sessions only ${summary.last28_cool_avg.toFixed(1)}%` : "";
    return `${sessionsLabel} · last 28 days avg ${summary.last28_avg.toFixed(1)}%${trend}${cool}`;
  }
  if (summary.last28_ef == null) return sessionsLabel;
  const improving = summary.prev28_ef != null && summary.last28_ef > summary.prev28_ef;
  const trend = summary.prev28_ef != null ? ` (${improving ? "improving" : "falling"} vs ${summary.prev28_ef.toFixed(2)})` : "";
  return `${sessionsLabel} · last 28 days avg ${summary.last28_ef.toFixed(2)}${trend}`;
}

export function DurabilityView({ athleteId, period }: { athleteId: string; period: "4w" | "16w" | "1y" | "all" }) {
  const [metric, setMetric] = useState<DurabilityMetric>("dec");
  const [sport, setSport] = useState<DurabilitySportFilter>("all");

  const { data } = useQuery({
    queryKey: ["durability", athleteId, sport, period],
    queryFn: () => getDurability(athleteId, sport, period),
    enabled: !!athleteId,
  });

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <div style={{ fontSize: 12.5, color: "var(--ink3)" }}>
        Bike and run with power only &middot; steady sessions: VI &le; 1.06 bike / 1.04 run, IF &le; 0.85, &ge; 60 min
      </div>

      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "18px 22px 14px" }}>
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 16, flexWrap: "wrap", marginBottom: 6 }}>
          <div>
            <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: "var(--ink)" }}>
              {metric === "dec" ? "Aerobic decoupling" : "Efficiency factor"}
            </h2>
            {data && <div style={{ fontSize: 12.5, color: "var(--ink3)", marginTop: 2 }}>{summaryLine(metric, data.summary.count, data.summary)}</div>}
          </div>
          <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
            <SegmentedControl
              size="sm"
              fill={false}
              options={METRICS.map((m) => ({ key: m.key, value: m.key, label: m.label }))}
              value={metric}
              onChange={setMetric}
            />
            <SegmentedControl
              size="sm"
              fill={false}
              options={SPORTS.map((s) => ({ key: s.key, value: s.key, label: s.label }))}
              value={sport}
              onChange={setSport}
            />
          </div>
        </div>

        <div style={{ display: "flex", alignItems: "center", gap: 16, flexWrap: "wrap", fontSize: 12, color: "var(--ink3)", marginBottom: 10 }}>
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <span style={{ width: 9, height: 9, borderRadius: "50%", background: SPORT_COLOR.ride }} />
            Ride
          </span>
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <span style={{ width: 9, height: 9, borderRadius: "50%", background: SPORT_COLOR.run }} />
            Run
          </span>
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <span style={{ width: 9, height: 9, borderRadius: "50%", border: `2px solid ${HOT_RING_COLOR}`, boxSizing: "border-box" }} />
            Hot session
          </span>
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <span style={{ width: 14, borderTop: "2px solid var(--ink2)" }} />
            28-day average
          </span>
        </div>

        {data && <DurabilityTrendChart sessions={data.sessions} rolling={data.rolling} metric={metric} sportFilter={sport} />}
      </div>

      {data && (sport === "all" || sport === "ride") && data.durability.ride && (
        <DurabilitySportTable sport="ride" section={data.durability.ride} />
      )}
      {data && (sport === "all" || sport === "run") && data.durability.run && (
        <DurabilitySportTable sport="run" section={data.durability.run} />
      )}
    </div>
  );
}
