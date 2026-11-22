import { extent } from "d3-array";
import { scaleLinear, scaleTime } from "d3-scale";
import { line } from "d3-shape";
import { useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";

// Wide aspect ratio for a full-width card - the SVG scales via viewBox + width:100%, matching
// WorkoutComparisonChart.tsx's own convention.
const WIDTH = 1200;
const HEIGHT = 260;
const MARGIN = { top: 10, right: 12, bottom: 24, left: 60 };

export interface AgreementTrendPoint {
  activityId: string;
  name: string;
  date: Date;
  /** The stat plotted on the y-axis - bias in v1 (issue #540 phase 5). */
  value: number;
  mae: number;
  rmse: number;
  pearsonR: number;
  withinTolerancePct: number;
  lagSeconds: number | null;
}

/** One activity's device-agreement bias plotted chronologically across the athlete's activities
 * (issue #540 phase 5) - structured like WorkoutComparisonChart.tsx (d3-array/d3-scale/
 * d3-shape, padded linear y-axis) with the same hover-crosshair technique (nearest-point lookup
 * via x.invert(), a dashed crosshair + circle marker, a readout below the chart). The readout
 * carries the point's full stat set, not just the plotted value. */
export function DeviceAgreementTrendChart({ points, formatValue, unit = "" }: {
  points: AgreementTrendPoint[];
  formatValue: (value: number) => string;
  /** Appended to the MAE/RMSE readout (e.g. "W", "bpm") - bias shares the field's unit. */
  unit?: string;
}) {
  const svgRef = useRef<SVGSVGElement>(null);
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);

  const chart = useMemo(() => {
    if (points.length === 0) {
      return null;
    }
    const innerWidth = WIDTH - MARGIN.left - MARGIN.right;
    const innerHeight = HEIGHT - MARGIN.top - MARGIN.bottom;

    const x = scaleTime().domain(extent(points, (p) => p.date) as [Date, Date]).range([0, innerWidth]);
    const [minValue, maxValue] = extent(points, (p) => p.value) as [number, number];
    const pad = (maxValue - minValue) * 0.15 || Math.max(1, maxValue * 0.05);
    const y = scaleLinear().domain([minValue - pad, maxValue + pad]).range([innerHeight, 0]);

    const seriesLine = line<AgreementTrendPoint>().x((p) => x(p.date)).y((p) => y(p.value));

    return { innerWidth, innerHeight, x, y, seriesLine, ticks: y.ticks(5), xTicks: x.ticks(Math.min(6, points.length)) };
  }, [points]);

  function handleMouseMove(event: React.MouseEvent<SVGSVGElement>) {
    if (!chart || !svgRef.current) return;
    const rect = svgRef.current.getBoundingClientRect();
    const relativeX = ((event.clientX - rect.left) / rect.width) * WIDTH - MARGIN.left;
    const targetX = chart.x.invert(relativeX);
    let closest = 0;
    let closestDist = Infinity;
    points.forEach((p, i) => {
      const d = Math.abs(p.date.getTime() - targetX.getTime());
      if (d < closestDist) {
        closestDist = d;
        closest = i;
      }
    });
    setHoverIndex(closest);
  }

  if (!chart) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>No activities in range have both sources yet.</div>;
  }

  const hovered = hoverIndex != null ? points[hoverIndex] : null;

  return (
    <div>
      <svg
        ref={svgRef}
        width="100%"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        onMouseMove={handleMouseMove}
        onMouseLeave={() => setHoverIndex(null)}
        role="img"
        aria-label="Device agreement trend chart"
      >
        <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
          {chart.ticks.map((tick) => (
            <g key={tick}>
              <line x1={0} x2={chart.innerWidth} y1={chart.y(tick)} y2={chart.y(tick)} stroke="var(--line)" strokeDasharray="3,3" />
              <text x={-8} y={chart.y(tick)} textAnchor="end" dominantBaseline="middle" fontSize={10} fill="var(--ink3)">
                {formatValue(tick)}
              </text>
            </g>
          ))}

          <path d={chart.seriesLine(points) ?? ""} fill="none" stroke="var(--ember, #e0703d)" strokeWidth={1.5} />
          {points.map((p, i) => (
            <circle key={p.activityId} cx={chart.x(p.date)} cy={chart.y(p.value)} r={hoverIndex === i ? 4 : 2.5} fill="var(--ember, #e0703d)" />
          ))}

          {hovered && (
            <line x1={chart.x(hovered.date)} x2={chart.x(hovered.date)} y1={0} y2={chart.innerHeight} stroke="var(--ink3)" strokeDasharray="2,2" />
          )}

          {chart.xTicks.map((tick) => (
            <text key={tick.toISOString()} x={chart.x(tick)} y={chart.innerHeight + 16} textAnchor="middle" fontSize={10} fill="var(--ink3)">
              {tick.toLocaleDateString(undefined, { month: "short", year: "2-digit" })}
            </text>
          ))}
        </g>
      </svg>

      {hovered && (
        <div style={{ marginTop: 6 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 14, fontSize: 12, color: "var(--ink2)" }}>
            <span className="mono" style={{ color: "var(--ink)", fontWeight: 700 }}>{formatValue(hovered.value)}</span>
            <span>{hovered.date.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" })}</span>
            <Link to={`/activities/${hovered.activityId}`} style={{ color: "var(--ember)", fontWeight: 600 }}>
              {hovered.name}
            </Link>
          </div>
          <div className="mono" style={{ display: "flex", gap: 14, flexWrap: "wrap", fontSize: 11.5, color: "var(--ink3)", marginTop: 4 }}>
            <span>MAE {hovered.mae.toFixed(1)}{unit}</span>
            <span>RMSE {hovered.rmse.toFixed(1)}{unit}</span>
            <span>r {hovered.pearsonR.toFixed(3)}</span>
            <span>{hovered.withinTolerancePct.toFixed(0)}% within tolerance</span>
            <span>lag {hovered.lagSeconds != null ? `${hovered.lagSeconds}s` : "—"}</span>
          </div>
        </div>
      )}
    </div>
  );
}
