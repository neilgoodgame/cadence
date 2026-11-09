import { useMemo } from "react";
import { extent } from "d3-array";
import { scaleLinear } from "d3-scale";
import type { StreamComparisonPoint, StreamComparisonStats } from "../../api/types";

const POINT_COLOR = "#3d7fd6";
const BIAS_COLOR = "#e0442e";
const LOA_COLOR = "var(--ink3)";

const WIDTH = 270;
const HEIGHT = 140;
const MARGIN = { top: 8, right: 10, bottom: 24, left: 36 };

/** x = mean of each pair, y = (b - a) - the standard Bland-Altman agreement plot (issue #503,
 * phase 4), with the bias and the two limits-of-agreement lines overlaid. No scatter primitive
 * exists elsewhere in this codebase, so this draws its own circles with the same
 * scaleLinear-based idiom every other chart in this directory uses. */
export function BlandAltmanScatter({
  points,
  stats,
  unit,
}: {
  points: StreamComparisonPoint[];
  stats: StreamComparisonStats;
  unit: string;
}) {
  const chart = useMemo(() => {
    if (points.length === 0) return null;
    const scatterPoints = points.map((p) => ({ mean: (p.a + p.b) / 2, diff: p.b - p.a }));

    const innerWidth = WIDTH - MARGIN.left - MARGIN.right;
    const innerHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
    const [xMin, xMax] = extent(scatterPoints, (p) => p.mean);
    const x = scaleLinear().domain([xMin ?? 0, xMax ?? 1]).nice().range([0, innerWidth]);

    const diffs = scatterPoints.map((p) => p.diff).concat([stats.loa_lower, stats.loa_upper]);
    const [yMin, yMax] = extent(diffs);
    const span = Math.max(Math.abs(yMin ?? 0), Math.abs(yMax ?? 0), 1) * 1.15;
    const y = scaleLinear().domain([-span, span]).range([innerHeight, 0]);

    return { innerWidth, innerHeight, scatterPoints, x, y, ticks: y.ticks(3) };
  }, [points, stats]);

  if (!chart) {
    return <div style={{ color: "var(--ink3)", fontSize: 12 }}>No overlapping data.</div>;
  }

  return (
    <div>
      <div style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 6 }}>Bland-Altman ({unit})</div>
      <svg width="100%" viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label="Bland-Altman scatter plot">
        <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
          {chart.ticks.map((tick) => (
            <g key={tick}>
              <line x1={0} x2={chart.innerWidth} y1={chart.y(tick)} y2={chart.y(tick)} stroke="var(--line)" strokeDasharray="3,3" />
              <text x={-6} y={chart.y(tick)} textAnchor="end" dominantBaseline="middle" fontSize={9} fill="var(--ink3)">
                {tick}
              </text>
            </g>
          ))}
          {chart.scatterPoints.map((p, i) => (
            <circle key={i} cx={chart.x(p.mean)} cy={chart.y(p.diff)} r={1.6} fill={POINT_COLOR} opacity={0.5} />
          ))}
          <line x1={0} x2={chart.innerWidth} y1={chart.y(stats.bias)} y2={chart.y(stats.bias)} stroke={BIAS_COLOR} strokeWidth={1.2} />
          <line
            x1={0}
            x2={chart.innerWidth}
            y1={chart.y(stats.loa_lower)}
            y2={chart.y(stats.loa_lower)}
            stroke={LOA_COLOR}
            strokeDasharray="4,3"
          />
          <line
            x1={0}
            x2={chart.innerWidth}
            y1={chart.y(stats.loa_upper)}
            y2={chart.y(stats.loa_upper)}
            stroke={LOA_COLOR}
            strokeDasharray="4,3"
          />
        </g>
      </svg>
      <div style={{ display: "flex", gap: 10, fontSize: 10, color: "var(--ink3)", marginTop: 4 }}>
        <span><span style={{ color: BIAS_COLOR }}>―</span> bias</span>
        <span><span style={{ color: LOA_COLOR }}>┄</span> ±1.96 SD</span>
      </div>
    </div>
  );
}
