import { scaleBand, scaleLinear } from "d3-scale";
import { line } from "d3-shape";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import type { RaceBlockPhase, RaceBlockWeek } from "../../api/types";
import {
  buildBlockChart,
  SEGMENT_COLOR,
  splitProjectedLine,
  type BlockChartMetric,
  type BlockChartPoint,
  type BlockChartSport,
} from "./raceBlockChart";

/** Headroom above the plot for the race flags. */
const TOP_PAD = 16;
/** Height of the phase strip drawn above the flags, when there are phases. */
const PHASE_H = 22;
/** Room below the baseline for the week-number labels. */
const LABEL_H = 18;

/** Tracks an element's rendered width, so the SVG can draw at its real pixel size rather than
 * scale a fixed viewBox - a scaled viewBox grows taller with the card's width and spills out of
 * its slot over whatever sits below it. */
function useElementWidth<T extends HTMLElement>(): [React.RefObject<T | null>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const observer = new ResizeObserver((entries) => setWidth(entries[0].contentRect.width));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}

/** Weekly bar + CTL-line training-block chart - hand-rolled SVG + d3-scale/d3-shape, same
 * convention as bestEfforts/DurabilityTrendChart.tsx. Bars stack per-sport segments (run, ride,
 * other) in sport colours, or show one sport when filtered; the race portion of a week is a
 * hatched overlay, a planned week is a dashed outline, the current week gets an ink ring, and
 * a comparison week renders as a ghost outline. The CTL line is solid where actual, dashed
 * where projected; the comparison block's CTL is a dashed grey line throughout. */
export function TrainingBlockChart({
  weeks,
  comparisonWeeks,
  metric,
  sport = "all",
  height = 210,
  currentIndex,
  selectedIndex,
  onSelectWeek,
  phases = [],
  compact = false,
}: {
  weeks: RaceBlockWeek[];
  comparisonWeeks: RaceBlockWeek[] | null;
  metric: BlockChartMetric;
  sport?: BlockChartSport;
  height?: number;
  currentIndex: number;
  selectedIndex: number | null;
  onSelectWeek: (index: number | null) => void;
  /** Base/Build/Peak/Taper bands drawn above the bars, aligned to their weeks. */
  phases?: RaceBlockPhase[];
  compact?: boolean;
}) {
  const chart = useMemo(() => buildBlockChart(weeks, comparisonWeeks, metric, sport), [weeks, comparisonWeeks, metric, sport]);
  const [containerRef, width] = useElementWidth<HTMLDivElement>();
  const hatchId = useId();
  const phaseH = !compact && phases.length > 0 ? PHASE_H : 0;
  const topPad = compact ? 2 : TOP_PAD + phaseH;
  const bottomPad = compact ? 0 : LABEL_H;

  return (
    <div ref={containerRef} style={{ width: "100%", height: height + topPad + bottomPad }}>
      {weeks.length > 0 && width > 0 && renderChart()}
    </div>
  );

  function renderChart() {
    const W = width;
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
        width={W}
        height={height + topPad + bottomPad}
        viewBox={`0 ${-topPad} ${W} ${height + topPad + bottomPad}`}
        role="img"
        aria-label="Training block chart"
        style={{ display: "block", overflow: "visible" }}
      >
        <defs>
          <pattern id={hatchId} width={6} height={6} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <line x1={0} y1={0} x2={0} y2={6} stroke="var(--ink)" strokeWidth={2} strokeOpacity={0.55} />
          </pattern>
        </defs>

        {phaseH > 0 &&
          phases.map((phase) => {
            // Weeks are 1-based; span from the first week's band start to the last week's band end.
            const px0 = x(phase.start_week - 1) ?? 0;
            const px1 = (x(phase.end_week - 1) ?? 0) + x.bandwidth();
            const isActive = currentIndex + 1 >= phase.start_week && currentIndex + 1 <= phase.end_week;
            const len = phase.end_week - phase.start_week + 1;
            const range = len > 1 ? `${phase.start_week}–${phase.end_week}` : `${phase.start_week}`;
            const top = -topPad;
            const clipId = `${hatchId}-phase-${phase.start_week}`;
            return (
              <g key={phase.name}>
                <clipPath id={clipId}>
                  <rect x={px0} y={top} width={Math.max(0, px1 - px0)} height={PHASE_H} />
                </clipPath>
                <title>{`${phase.name} · weeks ${range}`}</title>
                <line x1={px0} x2={px1} y1={top + 1} y2={top + 1} stroke={isActive ? "var(--ember)" : "var(--line)"} strokeWidth={2} />
                <text
                  x={px0}
                  y={top + 15}
                  fontSize={10}
                  fontWeight={700}
                  letterSpacing="0.06em"
                  fill={isActive ? "var(--ink)" : "var(--ink3)"}
                  className="mono"
                  clipPath={`url(#${clipId})`}
                >
                  {(len > 2 ? `${phase.name} · ${range}` : phase.name).toUpperCase()}
                </text>
              </g>
            );
          })}

        {chart.bars.map((bar) => {
          const bx = x(bar.index) ?? 0;
          const bw = x.bandwidth();
          const comparisonValue = chart.comparisonValues[bar.index];
          const isSelected = selectedIndex === bar.index;
          const isCurrent = bar.index === currentIndex;

          return (
            <g key={bar.index} style={{ cursor: "pointer" }} onClick={() => onSelectWeek(isSelected ? null : bar.index)}>
              {/* Transparent hit area, so an empty or short week is still clickable. */}
              <rect x={bx} y={0} width={bw} height={height} fill="transparent" />
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
              {bar.segments.map((seg) => {
                const color = SEGMENT_COLOR[seg.sport];
                return (
                  <rect
                    key={seg.sport}
                    x={bx}
                    y={y(seg.to)}
                    width={bw}
                    height={y(seg.from) - y(seg.to)}
                    fill={bar.isPlanned ? "none" : color}
                    stroke={bar.isPlanned ? color : "none"}
                    strokeDasharray={bar.isPlanned ? "3 3" : undefined}
                    opacity={bar.isPlanned || isSelected ? 1 : 0.75}
                  />
                );
              })}
              {bar.raceValue > 0 && (
                <rect x={bx} y={y(bar.raceValue)} width={bw} height={height - y(bar.raceValue)} fill={`url(#${hatchId})`} />
              )}
              {isCurrent && (
                <rect x={bx - 1} y={0} width={bw + 2} height={height} fill="none" stroke="var(--ink)" strokeWidth={2} />
              )}
              {!compact && bar.hasRace && (
                <text x={bx + bw / 2} y={-4} textAnchor="middle" fontSize={11} fill="var(--ember)">
                  ⚑
                </text>
              )}
            </g>
          );
        })}

        {!compact && (
          <>
            <line x1={0} x2={W} y1={height} y2={height} stroke="var(--ink3)" strokeWidth={1.5} />
            {chart.bars.map((bar) => {
              const isCurrent = bar.index === currentIndex;
              const isSelected = selectedIndex === bar.index;
              return (
                <text
                  key={bar.index}
                  x={(x(bar.index) ?? 0) + x.bandwidth() / 2}
                  y={height + 14}
                  textAnchor="middle"
                  fontSize={10.5}
                  fontWeight={isCurrent || isSelected ? 700 : 500}
                  fill={isCurrent ? "var(--ember)" : isSelected ? "var(--ink)" : "var(--ink3)"}
                  className="mono"
                >
                  {bar.index + 1}
                </text>
              );
            })}
          </>
        )}

        <path d={actualPath} fill="none" stroke="var(--ink)" strokeWidth={2} />
        <path d={projectedPath} fill="none" stroke="var(--ink)" strokeWidth={2} strokeDasharray="4 3" />
        {comparisonPath && <path d={comparisonPath} fill="none" stroke="var(--ink3)" strokeWidth={1.5} strokeDasharray="4 3" />}
      </svg>
    );
  }
}
