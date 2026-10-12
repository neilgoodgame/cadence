import { scaleBand, scaleLinear } from "d3-scale";
import { line } from "d3-shape";
import { useMemo } from "react";
import type { RaceBlockWeek } from "../../api/types";
import { buildBlockChart, splitProjectedLine, type BlockChartMetric, type BlockChartPoint } from "./raceBlockChart";

const W = 1200;

/** Weekly bar + CTL-line training-block chart - hand-rolled SVG + d3-scale/d3-shape, same
 * convention as bestEfforts/DurabilityTrendChart.tsx. Bars: the race portion of a week is a
 * darker overlay, a planned week is a dashed outline, the current week gets an ink ring, and
 * a comparison week renders as a ghost outline. The CTL line is solid where actual, dashed
 * where projected; the comparison block's CTL is a dashed grey line throughout. */
export function TrainingBlockChart({
  weeks,
  comparisonWeeks,
  metric,
  height = 210,
  currentIndex,
  selectedIndex,
  onSelectWeek,
  compact = false,
}: {
  weeks: RaceBlockWeek[];
  comparisonWeeks: RaceBlockWeek[] | null;
  metric: BlockChartMetric;
  height?: number;
  currentIndex: number;
  selectedIndex: number | null;
  onSelectWeek: (index: number | null) => void;
  compact?: boolean;
}) {
  const chart = useMemo(() => buildBlockChart(weeks, comparisonWeeks, metric), [weeks, comparisonWeeks, metric]);

  if (weeks.length === 0) return null;

  const x = scaleBand<number>()
    .domain(weeks.map((_, i) => i))
    .range([0, W])
    .padding(0.25);
  const y = scaleLinear().domain([0, chart.yMax]).range([height, 0]);
  const yCtl = scaleLinear().domain([0, chart.ctlMax]).range([height, 0]);

  const lineGen = line<BlockChartPoint>()
    .x((p) => (x(p.index) ?? 0) + x.bandwidth() / 2)
    .y((p) => yCtl(p.ctl));

  const { actual, projected } = splitProjectedLine(chart.ctlLine);
  const actualPath = actual.length > 0 ? lineGen(actual) ?? "" : "";
  const projectedPath = projected.length > 0 ? lineGen(projected) ?? "" : "";
  const comparisonPath = chart.comparisonCtlLine.length > 0 ? lineGen(chart.comparisonCtlLine) ?? "" : "";

  return (
    <svg
      width="100%"
      viewBox={`-4 -16 ${W + 8} ${height + 20}`}
      role="img"
      aria-label="Training block chart"
      style={{ display: "block", overflow: "visible" }}
    >
      {chart.bars.map((bar) => {
        const bx = x(bar.index) ?? 0;
        const bw = x.bandwidth();
        const comparisonValue = chart.comparisonValues[bar.index];
        const isSelected = selectedIndex === bar.index;
        const isCurrent = bar.index === currentIndex;

        return (
          <g key={bar.index} style={{ cursor: "pointer" }} onClick={() => onSelectWeek(isSelected ? null : bar.index)}>
            {comparisonValue != null && (
              <rect
                x={bx}
                y={y(comparisonValue)}
                width={bw}
                height={height - y(comparisonValue)}
                fill="none"
                stroke="var(--ink3)"
                strokeDasharray="3 3"
              />
            )}
            {bar.value != null && (
              <>
                <rect
                  x={bx}
                  y={y(bar.value)}
                  width={bw}
                  height={height - y(bar.value)}
                  fill={bar.isPlanned ? "none" : "var(--ember)"}
                  stroke={bar.isPlanned ? "var(--ember)" : "none"}
                  strokeDasharray={bar.isPlanned ? "3 3" : undefined}
                  opacity={bar.isPlanned ? 1 : isSelected ? 1 : 0.55}
                />
                {bar.raceValue > 0 && (
                  <rect x={bx} y={y(bar.raceValue)} width={bw} height={height - y(bar.raceValue)} fill="var(--ember)" />
                )}
              </>
            )}
            {isCurrent && (
              <rect x={bx - 1} y={0} width={bw + 2} height={height} fill="none" stroke="var(--ink)" strokeWidth={2} />
            )}
            {!compact && bar.hasRace && (
              <text x={bx + bw / 2} y={-6} textAnchor="middle" fontSize={11} fill="var(--ember)">
                ⚑
              </text>
            )}
          </g>
        );
      })}

      <path d={actualPath} fill="none" stroke="var(--ink)" strokeWidth={2} />
      <path d={projectedPath} fill="none" stroke="var(--ink)" strokeWidth={2} strokeDasharray="4 3" />
      {comparisonPath && <path d={comparisonPath} fill="none" stroke="var(--ink3)" strokeWidth={1.5} strokeDasharray="4 3" />}
    </svg>
  );
}
