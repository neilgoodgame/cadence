import { useMemo } from "react";
import { extent, max } from "d3-array";
import { scaleLinear } from "d3-scale";
import { line } from "d3-shape";
import type { StreamComparisonPoint } from "../../api/types";

const A_COLOR = "#2dd4bf";
const B_COLOR = "#e0442e";

const WIDTH = 560;
const HEIGHT = 160;
const MARGIN = { top: 8, right: 10, bottom: 20, left: 36 };

interface Point {
  x: number;
  v: number;
}

/** Overlay of both sides of a stream comparison (issue #503, phase 4) on the activity's own
 * shared timeline - same shared-scale, two-path idiom as MergeOverlayChart, but both series
 * come from one activity's two stored sources instead of two separate activities. */
export function StreamComparisonOverlayChart({
  points,
  unit,
  labelA = "a",
  labelB = "b",
}: {
  points: StreamComparisonPoint[];
  unit: string;
  labelA?: string;
  labelB?: string;
}) {
  const chart = useMemo(() => {
    if (points.length === 0) return null;
    const aPoints: Point[] = points.map((p) => ({ x: p.t, v: p.a }));
    const bPoints: Point[] = points.map((p) => ({ x: p.t, v: p.b }));

    const innerWidth = WIDTH - MARGIN.left - MARGIN.right;
    const innerHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
    const allPoints = [...aPoints, ...bPoints];
    const [xMin, xMax] = extent(allPoints, (p) => p.x);
    const x = scaleLinear().domain([xMin ?? 0, xMax ?? 1]).range([0, innerWidth]);
    const yMax = max(allPoints, (p) => p.v) ?? 1;
    const y = scaleLinear().domain([0, yMax * 1.1]).range([innerHeight, 0]);

    const lineFn = line<Point>()
      .x((p) => x(p.x))
      .y((p) => y(p.v));

    return {
      innerWidth,
      innerHeight,
      aPath: lineFn(aPoints),
      bPath: lineFn(bPoints),
      ticks: y.ticks(3),
      yAt: y,
    };
  }, [points]);

  if (!chart) {
    return <div style={{ color: "var(--ink3)", fontSize: 12, padding: "20px 0" }}>No overlapping data.</div>;
  }

  return (
    <div>
      <div style={{ display: "flex", gap: 10, fontSize: 11, color: "var(--ink3)", marginBottom: 6 }}>
        <span><span style={{ color: A_COLOR }}>●</span> {labelA} ({unit})</span>
        <span><span style={{ color: B_COLOR }}>●</span> {labelB} ({unit})</span>
      </div>
      <svg width="100%" viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label="Stream comparison overlay chart">
        <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
          {chart.ticks.map((tick) => (
            <g key={tick}>
              <line x1={0} x2={chart.innerWidth} y1={chart.yAt(tick)} y2={chart.yAt(tick)} stroke="var(--line)" strokeDasharray="3,3" />
              <text x={-6} y={chart.yAt(tick)} textAnchor="end" dominantBaseline="middle" fontSize={9} fill="var(--ink3)">
                {tick}
              </text>
            </g>
          ))}
          {chart.aPath && <path d={chart.aPath} fill="none" stroke={A_COLOR} strokeWidth={1.5} />}
          {chart.bPath && <path d={chart.bPath} fill="none" stroke={B_COLOR} strokeWidth={1.5} opacity={0.85} />}
        </g>
      </svg>
    </div>
  );
}
