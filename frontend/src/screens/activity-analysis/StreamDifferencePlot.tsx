import { useMemo } from "react";
import { extent } from "d3-array";
import { scaleLinear } from "d3-scale";
import { line } from "d3-shape";
import type { StreamComparisonPoint } from "../../api/types";

const DIFF_COLOR = "#3d7fd6";

const WIDTH = 270;
const HEIGHT = 140;
const MARGIN = { top: 8, right: 10, bottom: 20, left: 36 };

interface Point {
  x: number;
  v: number;
}

/** b - a over time (issue #503, phase 4) - the same sign convention as the comparison stats'
 * own bias (mean of b - a), so a flat line above zero reads the same way the headline bias
 * number does. Same chart skeleton as every other chart in this directory. */
export function StreamDifferencePlot({ points, unit }: { points: StreamComparisonPoint[]; unit: string }) {
  const chart = useMemo(() => {
    if (points.length === 0) return null;
    const diffPoints: Point[] = points.map((p) => ({ x: p.t, v: p.b - p.a }));

    const innerWidth = WIDTH - MARGIN.left - MARGIN.right;
    const innerHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
    const [xMin, xMax] = extent(diffPoints, (p) => p.x);
    const x = scaleLinear().domain([xMin ?? 0, xMax ?? 1]).range([0, innerWidth]);
    const [yMin, yMax] = extent(diffPoints, (p) => p.v);
    const span = Math.max(Math.abs(yMin ?? 0), Math.abs(yMax ?? 0), 1) * 1.1;
    const y = scaleLinear().domain([-span, span]).range([innerHeight, 0]);

    const lineFn = line<Point>()
      .x((p) => x(p.x))
      .y((p) => y(p.v));

    return { innerWidth, innerHeight, path: lineFn(diffPoints), ticks: y.ticks(3), yAt: y };
  }, [points]);

  if (!chart) {
    return <div style={{ color: "var(--ink3)", fontSize: 12 }}>No overlapping data.</div>;
  }

  return (
    <div>
      <div style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 6 }}>Difference (b − a, {unit})</div>
      <svg width="100%" viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label="Stream difference chart">
        <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
          {chart.ticks.map((tick) => (
            <g key={tick}>
              <line
                x1={0}
                x2={chart.innerWidth}
                y1={chart.yAt(tick)}
                y2={chart.yAt(tick)}
                stroke="var(--line)"
                strokeDasharray={tick === 0 ? undefined : "3,3"}
              />
              <text x={-6} y={chart.yAt(tick)} textAnchor="end" dominantBaseline="middle" fontSize={9} fill="var(--ink3)">
                {tick}
              </text>
            </g>
          ))}
          {chart.path && <path d={chart.path} fill="none" stroke={DIFF_COLOR} strokeWidth={1.5} />}
        </g>
      </svg>
    </div>
  );
}
