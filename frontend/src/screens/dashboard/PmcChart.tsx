import { extent, max } from "d3-array";
import { scalePoint, scaleLinear } from "d3-scale";
import { line } from "d3-shape";
import { useMemo } from "react";
import type { FitnessPoint } from "../../api/types";

const WIDTH = 760;
const HEIGHT = 220;
const MARGIN = { top: 10, right: 16, bottom: 24, left: 36 };

export function PmcChart({ points, dailyTss }: { points: FitnessPoint[]; dailyTss: Map<string, number> }) {
  const chart = useMemo(() => {
    if (points.length === 0) {
      return null;
    }
    const innerWidth = WIDTH - MARGIN.left - MARGIN.right;
    const innerHeight = HEIGHT - MARGIN.top - MARGIN.bottom;

    const x = scalePoint<string>()
      .domain(points.map((p) => p.date))
      .range([0, innerWidth]);

    const yMaxValue = max(points, (p) => Math.max(p.ctl, p.atl)) ?? 0;
    const yMaxTss = max(points, (p) => dailyTss.get(p.date) ?? 0) ?? 0;
    const yMax = Math.max(20, Math.ceil(Math.max(yMaxValue, yMaxTss / 2) / 20) * 20);

    const y = scaleLinear().domain([0, yMax]).range([innerHeight, 0]);

    const ctlLine = line<FitnessPoint>()
      .x((p) => x(p.date) ?? 0)
      .y((p) => y(p.ctl));
    const atlLine = line<FitnessPoint>()
      .x((p) => x(p.date) ?? 0)
      .y((p) => y(p.atl));

    const barWidth = Math.max(2, innerWidth / points.length - 2);
    const ticks = y.ticks(5);
    const [first, last] = extent(points, (p) => p.date);
    const labelEvery = Math.max(1, Math.floor(points.length / 6));

    return { innerWidth, innerHeight, x, y, ctlLine, atlLine, barWidth, ticks, first, last, labelEvery };
  }, [points, dailyTss]);

  if (!chart) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>No fitness data yet.</div>;
  }

  const { innerWidth, innerHeight, x, y, ctlLine, atlLine, barWidth, ticks, labelEvery } = chart;

  return (
    <svg width="100%" viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label="Performance management chart">
      <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
        {ticks.map((tick) => (
          <g key={tick}>
            <line x1={0} x2={innerWidth} y1={y(tick)} y2={y(tick)} stroke="var(--line)" strokeDasharray="3,3" />
            <text x={-8} y={y(tick)} textAnchor="end" dominantBaseline="middle" fontSize={10} fill="var(--ink3)">
              {tick}
            </text>
          </g>
        ))}

        {points.map((p) => {
          const tss = dailyTss.get(p.date) ?? 0;
          if (tss === 0) return null;
          const barHeight = Math.max(0, innerHeight - y(tss / 2));
          return (
            <rect
              key={p.date}
              x={(x(p.date) ?? 0) - barWidth / 2}
              y={innerHeight - barHeight}
              width={barWidth}
              height={barHeight}
              fill="var(--line)"
            />
          );
        })}

        <path d={atlLine(points) ?? ""} fill="none" stroke="#f0a02e" strokeWidth={2} />
        <path d={ctlLine(points) ?? ""} fill="none" stroke="#3d7fd6" strokeWidth={2} />

        {points
          .filter((_, i) => i % labelEvery === 0)
          .map((p) => (
            <text key={p.date} x={x(p.date) ?? 0} y={innerHeight + 16} textAnchor="middle" fontSize={10} fill="var(--ink3)">
              {new Date(p.date).toLocaleDateString(undefined, { month: "short", day: "numeric" })}
            </text>
          ))}
      </g>
    </svg>
  );
}
