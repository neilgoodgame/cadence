import { extent } from "d3-array";
import { scaleLinear, scaleTime } from "d3-scale";
import { curveStepAfter, line } from "d3-shape";
import { useMemo } from "react";
import { formatPace, parsePace } from "../lib/format";
import type { ThresholdFieldName, ThresholdHistoryPoint } from "../api/types";

// Wide aspect ratio for a full-width card - the SVG scales via viewBox + width:100%, so these
// just fix the ratio, not the literal rendered size.
const WIDTH = 1200;
const HEIGHT = 260;
const MARGIN = { top: 10, right: 12, bottom: 24, left: 60 };

interface Point {
  date: Date;
  value: number;
}

// threshold_pace's value is the display-ready "M:SS" string (see format.ts's parsePace) - every
// other field is already a plain number of watts.
function numericValue(field: ThresholdFieldName, value: number | string): number | null {
  return field === "threshold_pace" ? parsePace(String(value)) : Number(value);
}

function formatTick(field: ThresholdFieldName, value: number): string {
  return field === "threshold_pace" ? formatPace(value) : `${Math.round(value)}W`;
}

export function ThresholdHistoryChart({ field, points }: { field: ThresholdFieldName; points: ThresholdHistoryPoint[] }) {
  const chart = useMemo(() => {
    // `points` is most-recent-first (matches the ledger list above it) - the chart needs
    // chronological order to draw a sane step line.
    const chronological: Point[] = [...points]
      .reverse()
      // current_from, not effective_from - a step's boundary should be when the value actually
      // became current, not when its (possibly much earlier) source activity happened.
      .map((p) => ({ date: new Date(p.current_from), value: numericValue(field, p.value) }))
      .filter((p): p is Point => p.value != null);
    if (chronological.length === 0) {
      return null;
    }

    // Extends the last (current) value's step to today, so the line visually continues to the
    // present instead of stopping dead at its source activity's date - a threshold value holds
    // until superseded, it doesn't just vanish.
    const today = new Date();
    const last = chronological[chronological.length - 1];
    const withToday = today > last.date ? [...chronological, { date: today, value: last.value }] : chronological;

    const innerWidth = WIDTH - MARGIN.left - MARGIN.right;
    const innerHeight = HEIGHT - MARGIN.top - MARGIN.bottom;

    const x = scaleTime()
      .domain(extent(withToday, (p) => p.date) as [Date, Date])
      .range([0, innerWidth]);

    // Unlike PmcChart's zero-based domain (a cumulative training-load metric, where the zero
    // baseline matters), a threshold is a narrow-band value that drifts a little over time - a
    // zero-based axis would flatten the whole story into a thin line near the top. Padding
    // tightly around the actual min/max shows the shape of the change instead.
    const [minValue, maxValue] = extent(withToday, (p) => p.value) as [number, number];
    const pad = (maxValue - minValue) * 0.15 || Math.max(1, maxValue * 0.05);
    // Pace is seconds/km - lower is better, so its range is flipped: a faster (smaller) value
    // renders higher on the chart, matching "up is an improvement" for all three fields.
    const range: [number, number] = field === "threshold_pace" ? [0, innerHeight] : [innerHeight, 0];
    const y = scaleLinear().domain([minValue - pad, maxValue + pad]).range(range);

    const stepLine = line<Point>()
      .x((p) => x(p.date))
      .y((p) => y(p.value))
      .curve(curveStepAfter);

    const ticks = y.ticks(4);
    const xTicks = x.ticks(Math.min(5, chronological.length));

    return { innerWidth, innerHeight, x, y, stepLine, points: withToday, ticks, xTicks };
  }, [field, points]);

  if (!chart) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Not enough history yet to chart.</div>;
  }

  const { innerWidth, innerHeight, x, y, stepLine, points: chartPoints, ticks, xTicks } = chart;

  return (
    <svg width="100%" viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={`${field} history chart`}>
      <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
        {ticks.map((tick) => (
          <g key={tick}>
            <line x1={0} x2={innerWidth} y1={y(tick)} y2={y(tick)} stroke="var(--line)" strokeDasharray="3,3" />
            <text x={-8} y={y(tick)} textAnchor="end" dominantBaseline="middle" fontSize={10} fill="var(--ink3)">
              {formatTick(field, tick)}
            </text>
          </g>
        ))}

        <path d={stepLine(chartPoints) ?? ""} fill="none" stroke="var(--ember, #e0703d)" strokeWidth={1.25} />

        {xTicks.map((tick) => (
          <text key={tick.toISOString()} x={x(tick)} y={innerHeight + 16} textAnchor="middle" fontSize={10} fill="var(--ink3)">
            {tick.toLocaleDateString(undefined, { month: "short", year: "numeric" })}
          </text>
        ))}
      </g>
    </svg>
  );
}
