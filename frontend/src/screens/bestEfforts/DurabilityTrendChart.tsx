import { extent } from "d3-array";
import { scaleLinear, scaleTime } from "d3-scale";
import { line } from "d3-shape";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { monthTickStep } from "../thresholdChart";
import {
  HOT_RING_COLOR,
  SPORT_COLOR,
  metricAxisTitle,
  metricTickLabel,
  metricValue,
  visibleRollingSeries,
  yDomain,
  type DurabilityMetric,
  type DurabilitySportFilter,
} from "./durabilityChart";
import type { DurabilityResponse, DurabilitySession } from "../../api/types";

const W = 1200;
const H = 240;
const VIEWBOX_H = 250;
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

const BAND_BG = { good: "rgba(47,166,106,0.08)", moderate: "rgba(240,160,46,0.08)", high: "rgba(224,68,46,0.08)" };

function fmtTooltipDate(d: Date): string {
  return `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
}

export function DurabilityTrendChart({
  sessions,
  rolling,
  metric,
  sportFilter,
}: {
  sessions: DurabilitySession[];
  rolling: DurabilityResponse["rolling"];
  metric: DurabilityMetric;
  sportFilter: DurabilitySportFilter;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const navigate = useNavigate();

  const chart = useMemo(() => {
    const points = sessions
      .map((s) => ({ session: s, date: new Date(s.date), value: metricValue(s, metric) }))
      .filter((p): p is { session: DurabilitySession; date: Date; value: number } => p.value != null)
      .sort((a, b) => a.date.getTime() - b.date.getTime());
    if (points.length === 0) return null;

    const [t0, t1] = extent(points.map((p) => p.date)) as [Date, Date];
    const x = scaleTime().domain([t0, t1]).range([0, W]);
    const [yMin, yMax] = yDomain(metric, sessions);
    const y = scaleLinear().domain([yMin, yMax]).range([H, 0]);

    const yTicks = (metric === "dec" ? [0, 5, 10, 15] : y.ticks(4)).map((v) => ({ value: v, y: y(v) }));

    const spanDays = (t1.getTime() - t0.getTime()) / 86_400_000;
    const step = monthTickStep(spanDays);
    const xTicks: { date: Date; label: string; x: number }[] = [];
    for (
      let m = new Date(t0.getFullYear(), Math.ceil(t0.getMonth() / step) * step, 1);
      m.getTime() <= t1.getTime();
      m = new Date(m.getFullYear(), m.getMonth() + step, 1)
    ) {
      if (m.getTime() < t0.getTime()) continue;
      xTicks.push({ date: m, label: MONTHS[m.getMonth()], x: x(m) });
    }

    const rollingLines = visibleRollingSeries(rolling, sportFilter).map((series) => {
      const linePoints = series.points
        .map((p) => ({ date: new Date(p.date), value: metric === "dec" ? p.decoupling_pct : p.ef }))
        .filter((p): p is { date: Date; value: number } => p.value != null);
      const lineGen = line<{ date: Date; value: number }>()
        .x((p) => x(p.date))
        .y((p) => y(p.value));
      return { sport: series.sport, path: lineGen(linePoints) ?? "" };
    });

    const plotted = points.map((p, i) => ({ index: i, x: x(p.date), y: y(p.value), session: p.session }));

    return { x, y, yTicks, xTicks, rollingLines, plotted };
  }, [sessions, rolling, metric, sportFilter]);

  if (!chart) {
    return (
      <div style={{ color: "var(--ink3)", fontSize: 13, padding: "24px 0" }}>
        No qualifying sessions in this period &mdash; rides and runs need power, VI &le; limit, IF &le; 0.85 and
        60+ min steady.
      </div>
    );
  }

  const { yTicks, xTicks, rollingLines, plotted } = chart;
  const pctX = (xv: number) => (xv / W) * 100;
  const pctY = (yv: number) => ((5 + yv) / VIEWBOX_H) * 100;
  const hoverPoint = hover != null ? plotted[hover] : null;

  return (
    <div style={{ display: "grid", gridTemplateColumns: "22px 48px minmax(0,1fr)" }}>
      <div style={{ position: "relative" }}>
        <div
          style={{
            position: "absolute", left: "50%", top: "50%", transform: "translate(-50%,-50%) rotate(-90deg)",
            whiteSpace: "nowrap", fontSize: 12, fontWeight: 700, color: "var(--ink2)",
          }}
        >
          {metricAxisTitle(metric)}
        </div>
      </div>
      <div style={{ position: "relative" }}>
        {yTicks.map((t) => (
          <div
            key={t.value}
            className="mono"
            style={{ position: "absolute", right: 8, top: `${pctY(t.y)}%`, transform: "translateY(-50%)", fontSize: 11, color: "var(--ink2)", whiteSpace: "nowrap" }}
          >
            {metricTickLabel(metric, t.value)}
          </div>
        ))}
      </div>
      <div style={{ position: "relative", minWidth: 0 }}>
        <svg width="100%" viewBox={`0 0 ${W} ${VIEWBOX_H}`} role="img" aria-label="Durability trend chart" style={{ display: "block", overflow: "visible" }}>
          <g transform="translate(0,5)">
            {metric === "dec" && (
              <>
                <rect x={0} y={chart.y(15)} width={W} height={chart.y(10) - chart.y(15)} fill={BAND_BG.high} />
                <rect x={0} y={chart.y(10)} width={W} height={chart.y(5) - chart.y(10)} fill={BAND_BG.moderate} />
                <rect x={0} y={chart.y(5)} width={W} height={chart.y(0) - chart.y(5)} fill={BAND_BG.good} />
                <text x={W - 4} y={chart.y(15) + 14} textAnchor="end" fontSize={11} fill="var(--ink3)">High</text>
                <text x={W - 4} y={(chart.y(10) + chart.y(5)) / 2 + 4} textAnchor="end" fontSize={11} fill="var(--ink3)">Moderate</text>
                <text x={W - 4} y={chart.y(0) - 6} textAnchor="end" fontSize={11} fill="var(--ink3)">Good</text>
              </>
            )}
            {yTicks.map((t) => (
              <rect key={t.value} x={0} y={t.y - 0.5} width={W} height={1} fill="var(--line)" />
            ))}
            <rect x={0} y={0} width={1.5} height={H} fill="var(--ink3)" />
            <rect x={0} y={H - 1} width={W} height={1.5} fill="var(--ink3)" />
            {rollingLines.map((rl) => (
              <path key={rl.sport} d={rl.path} fill="none" stroke={SPORT_COLOR[rl.sport]} strokeWidth={2.5} opacity={0.9} />
            ))}
            {plotted.map((p) => {
              const sport = p.session.sport;
              const hovered = hover === p.index;
              return (
                <g key={p.index}>
                  {p.session.hot && (
                    <circle cx={p.x} cy={p.y} r={hovered ? 9 : 7} fill="none" stroke={HOT_RING_COLOR} strokeWidth={2} />
                  )}
                  <circle cx={p.x} cy={p.y} r={hovered ? 5.5 : 4} fill={SPORT_COLOR[sport]} stroke="var(--card)" strokeWidth={1.5} />
                  <circle
                    cx={p.x}
                    cy={p.y}
                    r={11}
                    fill="transparent"
                    style={{ cursor: "pointer" }}
                    onMouseEnter={() => setHover(p.index)}
                    onMouseLeave={() => setHover(null)}
                    onClick={() => navigate(`/activities/${p.session.activity_id}`)}
                  />
                </g>
              );
            })}
          </g>
        </svg>

        {hoverPoint && (
          <div
            style={{
              position: "absolute",
              left: `${pctX(hoverPoint.x)}%`,
              top: `${pctY(hoverPoint.y)}%`,
              transform: `translate(${pctX(hoverPoint.x) > 78 ? "-100%" : pctX(hoverPoint.x) < 18 ? "0" : "-50%"}, calc(-100% - 14px))`,
              pointerEvents: "none",
              background: "var(--card)", border: "1px solid var(--line)", borderRadius: 10,
              padding: "10px 12px", boxShadow: "0 8px 24px rgba(0,0,0,0.18)", minWidth: 200, zIndex: 2,
            }}
          >
            <div style={{ fontSize: 13, fontWeight: 700, color: "var(--ink)" }}>{hoverPoint.session.name}</div>
            <div className="mono" style={{ fontSize: 11.5, color: "var(--ink3)", marginTop: 2 }}>
              {fmtTooltipDate(new Date(hoverPoint.session.date))}
            </div>
            <div className="mono" style={{ fontSize: 15, fontWeight: 700, color: "var(--ink)", marginTop: 4 }}>
              {metric === "dec" ? `Decoupling ${hoverPoint.session.decoupling_pct.toFixed(1)}%` : `EF ${hoverPoint.session.ef?.toFixed(2)}`}
            </div>
            <div style={{ fontSize: 11.5, color: "var(--ink2)", marginTop: 2 }}>
              {metric === "dec"
                ? hoverPoint.session.ef != null && `EF ${hoverPoint.session.ef.toFixed(2)} W/bpm`
                : `Decoupling ${hoverPoint.session.decoupling_pct.toFixed(1)}%`}
            </div>
            {hoverPoint.session.hot && (
              <div style={{ fontSize: 11.5, fontWeight: 600, color: HOT_RING_COLOR, marginTop: 4 }}>
                Hot
                {hoverPoint.session.avg_temp != null && ` · ${hoverPoint.session.avg_temp} °C air`}
                {hoverPoint.session.avg_core != null && ` · ${hoverPoint.session.avg_core} °C core`}
              </div>
            )}
          </div>
        )}
      </div>

      <div />
      <div />
      <div style={{ position: "relative", height: 30 }}>
        {xTicks.map((t) => (
          <div
            key={t.date.toISOString()}
            style={{ position: "absolute", left: `${(t.x / W) * 100}%`, top: 4, transform: "translateX(-50%)", fontSize: 11, color: "var(--ink3)", whiteSpace: "nowrap" }}
          >
            {t.label}
          </div>
        ))}
      </div>
    </div>
  );
}
