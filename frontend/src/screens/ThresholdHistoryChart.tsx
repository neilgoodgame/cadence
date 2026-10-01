import { extent } from "d3-array";
import { scaleLinear, scaleTime } from "d3-scale";
import { curveStepAfter, line } from "d3-shape";
import { useMemo, useState } from "react";
import { formatPace } from "../lib/format";
import { FIELDS } from "../lib/thresholdFields";
import {
  bestValue,
  clipToRange,
  isImprovement,
  monthTickStep,
  numericValue,
  tooltipDays,
  tooltipDeltaText,
  tooltipSourceText,
  type ChartRange,
  type ClippedPoint,
} from "./thresholdChart";
import type { ThresholdFieldName, ThresholdHistoryPoint } from "../api/types";

// Plot size, not the literal rendered size - the SVG scales via viewBox + width:100%. The
// viewBox is W x (H+10): a few px of top breathing room for the end dot/pill plus a hairline
// under the baseline for the x axis itself.
const W = 1200;
const H = 300;
const VIEWBOX_H = 310;
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const RANGES: { key: ChartRange; label: string }[] = [
  { key: "all", label: "All time" },
  { key: "1y", label: "1 year" },
  { key: "16w", label: "16 weeks" },
];

function formatTick(field: ThresholdFieldName, value: number): string {
  return field === "threshold_pace" ? formatPace(value) : `${Math.round(value)} W`;
}

function fmtTooltipDate(d: Date): string {
  return `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
}

const rangeTabStyle = (active: boolean): React.CSSProperties => ({
  padding: "5px 11px",
  borderRadius: 7,
  fontSize: 12,
  fontWeight: 600,
  cursor: "pointer",
  color: active ? "var(--ink)" : "var(--ink3)",
  background: active ? "var(--card)" : "transparent",
  boxShadow: active ? "0 1px 2px rgba(0,0,0,0.14)" : "none",
});

interface RealPoint extends ClippedPoint {
  real: true;
  hasSource: boolean;
  previousValue: number | null;
  nextDate: Date;
}

/** This field's history, redesigned (Oct 2026 handoff): a range toggle (all time / 1 year / 16
 * weeks), alternating-year bands, a dashed best-in-range line, hollow dots for a dropped value
 * vs filled dots for an improvement, and a hover tooltip with the delta/days-in-effect/source.
 * Axis text is HTML overlaid on percentages, not SVG <text> - at 10px, SVG text scales down to
 * near-illegible in a narrow pane since the whole SVG scales with the viewBox. */
export function ThresholdHistoryChart({ field, points }: { field: ThresholdFieldName; points: ThresholdHistoryPoint[] }) {
  const [range, setRange] = useState<ChartRange>("all");
  const [hover, setHover] = useState<number | null>(null);

  const chart = useMemo(() => {
    // `points` is most-recent-first (matches the ledger list above it) - chronological order is
    // needed to draw a sane step line and to compute each point's own previous/next neighbour.
    const chronological = [...points]
      .reverse()
      .map((p) => ({
        // current_from, not effective_from - a step's boundary is when the value actually
        // became current, not when its (possibly much earlier) source activity happened.
        date: new Date(p.current_from),
        value: numericValue(field, p.value),
        hasSource: p.source_activity_id != null,
      }))
      .filter((p): p is { date: Date; value: number; hasSource: boolean } => p.value != null);
    if (chronological.length === 0) {
      return null;
    }

    // Deltas and "next change" dates are computed on the full series before range-clipping, so
    // a point right at the clip boundary still shows its true delta/days-in-effect rather than
    // one computed against whatever the clip left behind.
    const today = new Date();
    const withNeighbours: RealPoint[] = chronological.map((p, i) => ({
      ...p,
      real: true,
      previousValue: i > 0 ? chronological[i - 1].value : null,
      nextDate: chronological[i + 1]?.date ?? today,
    }));

    const clipped = clipToRange(withNeighbours, range, today);
    const last = clipped[clipped.length - 1];
    // Extends the last (current) value's step to today, so the line visually continues to the
    // present instead of stopping dead at its source activity's date - a threshold value holds
    // until superseded, it doesn't just vanish.
    const series: ClippedPoint[] = today > last.date ? [...clipped, { date: today, value: last.value, real: false }] : clipped;

    const t0 = series[0].date;
    const t1 = series[series.length - 1].date;
    const x = scaleTime().domain([t0, t1]).range([0, W]);

    const values = series.map((p) => p.value);
    const [minValue, maxValue] = extent(values) as [number, number];
    const pad = (maxValue - minValue) * 0.18 || Math.max(1, maxValue * 0.05);
    // Pace is seconds/km - lower is better, so its range is flipped: a faster (smaller) value
    // renders higher on the chart, matching "up is an improvement" for every field.
    const lowerIsBetter = field === "threshold_pace";
    const yRange: [number, number] = lowerIsBetter ? [0, H] : [H, 0];
    const y = scaleLinear().domain([minValue - pad, maxValue + pad]).range(yRange);

    const stepLine = line<ClippedPoint>()
      .x((p) => x(p.date))
      .y((p) => y(p.value))
      .curve(curveStepAfter);
    const path = stepLine(series) ?? "";
    const area = `${path} V ${H} H ${x(series[0].date)} Z`;

    const yTicks = y.ticks(4).map((v) => ({ value: v, y: y(v) }));

    const spanDays = (t1.getTime() - t0.getTime()) / 86_400_000;
    const step = monthTickStep(spanDays);
    const xTicks: { date: Date; label: string; x: number; isJanuary: boolean }[] = [];
    for (
      let m = new Date(t0.getFullYear(), Math.ceil(t0.getMonth() / step) * step, 1);
      m.getTime() <= t1.getTime();
      m = new Date(m.getFullYear(), m.getMonth() + step, 1)
    ) {
      if (m.getTime() < t0.getTime()) continue;
      xTicks.push({ date: m, label: MONTHS[m.getMonth()], x: x(m), isJanuary: m.getMonth() === 0 });
    }

    const yearBands: { startX: number; endX: number; label: string }[] = [];
    for (let yr = t0.getFullYear(); yr <= t1.getFullYear(); yr++) {
      const xa = Math.max(0, x(new Date(yr, 0, 1)));
      const xb = Math.min(W, x(new Date(yr + 1, 0, 1)));
      if (xb - xa < 1) continue;
      yearBands.push({ startX: xa, endX: xb, label: xb - xa > 90 ? String(yr) : "" });
    }

    // Change points: one per real ledger entry within the clipped range (the synthetic
    // window-start point isn't a real entry, so it never gets a dot or counts toward best).
    const real = clipped.filter((p): p is RealPoint => p.real);
    const best = bestValue(
      field,
      real.map((p) => p.value),
    );
    const bestPoint = best != null ? real.find((p) => p.value === best) : undefined;
    const currentIsBest = best != null && last.value === best;

    const changePoints = real.map((p, i) => ({
      index: i,
      x: x(p.date),
      y: y(p.value),
      improved: isImprovement(field, p.value, p.previousValue),
    }));

    const endPoint = series[series.length - 1];
    const endX = x(endPoint.date);
    const endY = y(last.value);

    return {
      path,
      area,
      yTicks,
      xTicks,
      yearBands,
      real,
      best,
      bestPoint,
      currentIsBest,
      changePoints,
      endX,
      endY,
      endValue: last.value,
      x,
      y,
    };
  }, [field, points, range]);

  if (!chart) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Not enough history yet to chart.</div>;
  }

  const { path, area, yTicks, xTicks, yearBands, real, best, bestPoint, currentIsBest, changePoints, endX, endY, endValue, x, y } = chart;

  const pctX = (xv: number) => (xv / W) * 100;
  const pctY = (yv: number) => ((5 + yv) / VIEWBOX_H) * 100;

  const hoverPoint = hover != null ? real[hover] : null;
  const fieldLabel = FIELDS.find((f) => f.field === field)!.label;
  const yAxisTitle = field === "threshold_pace" ? "Threshold pace (min/km, faster ↑)" : `${fieldLabel} (W)`;

  const bestLegendText =
    best == null
      ? "Best"
      : `Best in range ${formatTick(field, best)}${bestPoint ? ` · ${MONTHS[bestPoint.date.getMonth()]} ${bestPoint.date.getFullYear()}` : ""}${currentIsBest ? " (current)" : ""}`;

  return (
    <div style={{ padding: "18px 24px 14px" }}>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, flexWrap: "wrap", marginBottom: 10 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 16, flexWrap: "wrap", fontSize: 12, color: "var(--ink3)" }}>
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <span style={{ width: 9, height: 9, borderRadius: "50%", background: "var(--ember)" }} />
            New best effort
          </span>
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <span style={{ width: 9, height: 9, borderRadius: "50%", border: "1.5px solid var(--ink3)", boxSizing: "border-box" }} />
            Dropped (older best aged out)
          </span>
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <span style={{ width: 14, borderTop: "1.5px dashed var(--ink3)" }} />
            {bestLegendText}
          </span>
        </div>
        <div style={{ display: "flex", gap: 3, background: "var(--canvas)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
          {RANGES.map((r) => (
            <div key={r.key} onClick={() => { setRange(r.key); setHover(null); }} style={rangeTabStyle(r.key === range)}>
              {r.label}
            </div>
          ))}
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "22px 58px minmax(0,1fr)" }}>
        <div style={{ position: "relative" }}>
          <div
            style={{
              position: "absolute", left: "50%", top: "50%", transform: "translate(-50%,-50%) rotate(-90deg)",
              whiteSpace: "nowrap", fontSize: 12, fontWeight: 700, color: "var(--ink2)",
            }}
          >
            {yAxisTitle}
          </div>
        </div>
        <div style={{ position: "relative" }}>
          {yTicks.map((t) => (
            <div
              key={t.value}
              style={{
                position: "absolute", right: 8, top: `${pctY(t.y)}%`, transform: "translateY(-50%)",
                fontSize: 11, color: "var(--ink2)", whiteSpace: "nowrap",
              }}
              className="mono"
            >
              {formatTick(field, t.value)}
            </div>
          ))}
        </div>
        <div style={{ position: "relative", minWidth: 0 }}>
          {/* overflow:hidden (not the default CSS "visible" a root <svg> otherwise gets) - the
              January x-tick is drawn 22px tall vs 10px for other months, which at this viewBox's
              scale bleeds past the plot's own box and crosses through the month-label row right
              below it if left unclipped. A few units of edge-dot clipping at the very first/last
              point (x=0/x=W exactly) is the acceptable trade. */}
          <svg width="100%" viewBox={`0 0 ${W} ${VIEWBOX_H}`} role="img" aria-label={`${field} history chart`} style={{ display: "block", overflow: "hidden" }}>
            <defs>
              <linearGradient id="thrArea" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--ember)" stopOpacity={0.24} />
                <stop offset="100%" stopColor="var(--ember)" stopOpacity={0} />
              </linearGradient>
            </defs>
            <g transform="translate(0,5)">
              {yearBands.map((b) => (
                <rect key={b.startX} x={b.startX} y={0} width={b.endX - b.startX} height={H} fill={b.label ? "var(--elev)" : "transparent"} />
              ))}
              {yTicks.map((t) => (
                <g key={t.value}>
                  <rect x={0} y={t.y - 0.5} width={W} height={1} fill="var(--line)" />
                  <rect x={-8} y={t.y - 0.75} width={8} height={1.5} fill="var(--ink3)" />
                </g>
              ))}
              <rect x={0} y={0} width={1.5} height={H} fill="var(--ink3)" />
              <rect x={0} y={H - 1} width={W} height={1.5} fill="var(--ink3)" />
              {xTicks.map((t) => (
                <rect key={t.date.toISOString()} x={t.x - 0.75} y={H} width={1.5} height={t.isJanuary ? 22 : 10} fill="var(--ink3)" />
              ))}
              {best != null && (
                <rect x={0} y={y(best) - 0.5} width={W} height={1} fill="var(--ink3)" opacity={0.8} />
              )}
              <path d={area} fill="url(#thrArea)" stroke="none" />
              <path d={path} fill="none" stroke="var(--ember)" strokeWidth={2} strokeLinejoin="round" />
              {changePoints.map((p) => (
                <g key={p.index}>
                  <circle
                    cx={p.x}
                    cy={p.y}
                    r={hover === p.index ? 6 : 4}
                    fill={p.improved ? "var(--ember)" : "var(--card)"}
                    stroke={p.improved ? "var(--ember)" : "var(--ink3)"}
                    strokeWidth={1.75}
                    style={{ transition: "r 0.12s" }}
                  />
                  <circle
                    cx={p.x}
                    cy={p.y}
                    r={14}
                    fill="transparent"
                    style={{ cursor: "pointer" }}
                    onMouseEnter={() => setHover(p.index)}
                    onMouseLeave={() => setHover(null)}
                  />
                </g>
              ))}
              <circle cx={endX} cy={endY} r={4.5} fill="var(--card)" stroke="var(--ember)" strokeWidth={2.5} />
            </g>
          </svg>

          <div
            style={{
              position: "absolute", left: `${pctX(endX)}%`, top: `${pctY(endY)}%`,
              transform: "translate(-100%, calc(-100% - 10px))",
              fontSize: 11.5, fontWeight: 700, color: "var(--ink)",
              background: "var(--card)", border: "1px solid var(--ember)", borderRadius: 6,
              padding: "3px 8px", whiteSpace: "nowrap", pointerEvents: "none",
            }}
            className="mono"
          >
            Now {formatTick(field, endValue)}
          </div>

          {hoverPoint && (
            <div
              style={{
                position: "absolute",
                left: `${pctX(x(hoverPoint.date))}%`,
                top: `${pctY(y(hoverPoint.value))}%`,
                transform: `translate(${pctX(x(hoverPoint.date)) > 78 ? "-100%" : pctX(x(hoverPoint.date)) < 18 ? "0" : "-50%"}, calc(-100% - 14px))`,
                pointerEvents: "none",
                background: "var(--card)", border: "1px solid var(--line)", borderRadius: 10,
                padding: "10px 12px", boxShadow: "0 8px 24px rgba(0,0,0,0.18)", minWidth: 190, zIndex: 2,
              }}
            >
              <div className="mono" style={{ fontSize: 15, fontWeight: 700, color: "var(--ink)" }}>
                {formatTick(field, hoverPoint.value)}
              </div>
              <div
                style={{
                  fontSize: 12, fontWeight: 600, marginTop: 2,
                  color: isImprovement(field, hoverPoint.value, hoverPoint.previousValue) ? "var(--ember)" : "var(--ink2)",
                }}
              >
                {tooltipDeltaText(field, hoverPoint.value, hoverPoint.previousValue)}
              </div>
              <div style={{ fontSize: 11.5, color: "var(--ink3)", marginTop: 4 }}>
                From {fmtTooltipDate(hoverPoint.date)} · {tooltipDays(hoverPoint.date, hoverPoint.nextDate)} day
                {tooltipDays(hoverPoint.date, hoverPoint.nextDate) === 1 ? "" : "s"} in effect
              </div>
              <div style={{ fontSize: 11.5, color: "var(--ink2)", marginTop: 2 }}>
                {tooltipSourceText(hoverPoint.hasSource, isImprovement(field, hoverPoint.value, hoverPoint.previousValue))}
              </div>
            </div>
          )}
        </div>

        <div />
        <div />
        <div style={{ position: "relative", height: 40 }}>
          {xTicks.map((t) => (
            <div
              key={t.date.toISOString()}
              style={{ position: "absolute", left: `${(t.x / W) * 100}%`, top: 4, transform: "translateX(-50%)", fontSize: 11, color: "var(--ink3)", whiteSpace: "nowrap" }}
            >
              {t.label}
            </div>
          ))}
          {yearBands
            .filter((b) => b.label)
            .map((b) => (
              <div
                key={b.startX}
                style={{
                  position: "absolute", left: `${((b.startX + b.endX) / 2 / W) * 100}%`, top: 21,
                  transform: "translateX(-50%)", fontSize: 12, fontWeight: 700, color: "var(--ink2)", whiteSpace: "nowrap",
                }}
              >
                {b.label}
              </div>
            ))}
        </div>

        <div />
        <div />
        <div style={{ textAlign: "center", fontSize: 12, fontWeight: 700, color: "var(--ink2)", paddingTop: 2 }}>Date value took effect</div>
      </div>
    </div>
  );
}
