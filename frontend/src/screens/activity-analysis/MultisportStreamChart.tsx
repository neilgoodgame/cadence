import { useMemo, useState } from "react";
import { useQueries } from "@tanstack/react-query";
import { max } from "d3-array";
import { scaleLinear } from "d3-scale";
import { area, line } from "d3-shape";
import { getActivity, getStreams } from "../../api/activities";
import type { Activity } from "../../api/types";
import { formatDuration } from "../../lib/format";
import { sportColor, sportLabel } from "../../lib/sportColors";

type Metric = "power" | "heartrate" | "cadence";

const METRICS: { key: Metric; label: string; unit: string }[] = [
  { key: "power", label: "Power", unit: "w" },
  { key: "heartrate", label: "Heart Rate", unit: "bpm" },
  { key: "cadence", label: "Cadence", unit: "rpm" },
];

const CHART_WIDTH = 900;
const ROW_HEIGHT = 88;
const MARGIN = { top: 6, right: 4, bottom: 6, left: 40 };
const LABEL_COLUMN = 118;
// A short leg (a transition) still needs enough width to read and hover.
const MIN_ROW_FRACTION = 0.08;

interface Sample {
  t: number;
  power: number | null;
  heartrate: number | null;
  cadence: number | null;
  altitude: number | null;
}

interface Leg {
  activity: Activity;
  samples: Sample[];
}

/** The multisport parent's stream view: one row per leg, stacked in session order.
 * Rows share the metric toggle and y-scale so values compare across legs, and each
 * row's width is proportional to the leg's duration so the stack reads as a timeline.
 */
export function MultisportStreamChart({ activity }: { activity: Activity }) {
  const [metric, setMetric] = useState<Metric>(activity.avg_power ? "power" : "heartrate");
  const [hover, setHover] = useState<{ leg: number; index: number } | null>(null);

  const childQueries = useQueries({
    queries: activity.child_activity_ids.map((id) => ({
      queryKey: ["activity", id],
      queryFn: () => getActivity(id),
    })),
  });
  const children = childQueries.map((q) => q.data);
  const streamQueries = useQueries({
    queries: activity.child_activity_ids.map((id) => ({
      queryKey: ["activity-streams-chart", id],
      queryFn: () => getStreams(id, ["time", "power", "heartrate", "cadence", "altitude"], "medium"),
    })),
  });

  const legs: Leg[] | null = useMemo(() => {
    if (children.some((c) => !c) || streamQueries.some((q) => !q.data)) return null;
    return children.map((child, i) => {
      const fields = streamQueries[i].data!.fields;
      const length = fields.time?.length ?? 0;
      return {
        activity: child!,
        samples: Array.from({ length }, (_, j) => ({
          t: fields.time![j] ?? 0,
          power: fields.power?.[j] ?? null,
          heartrate: fields.heartrate?.[j] ?? null,
          cadence: fields.cadence?.[j] ?? null,
          altitude: fields.altitude?.[j] ?? null,
        })),
      };
    });
    // streamQueries/childQueries are new arrays every render; their data is what matters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [children.map((c) => c?.id).join(), streamQueries.map((q) => q.dataUpdatedAt).join()]);

  const layout = useMemo(() => {
    if (!legs) return null;
    const yMax = max(legs.flatMap((leg) => leg.samples.map((s) => s[metric]).filter((v): v is number => v != null))) ?? 1;
    const innerHeight = ROW_HEIGHT - MARGIN.top - MARGIN.bottom;
    const y = scaleLinear().domain([0, yMax * 1.1]).range([innerHeight, 0]);

    const totalTime = legs.reduce((sum, leg) => sum + leg.activity.moving_time, 0);
    const rawFractions = legs.map((leg) => leg.activity.moving_time / Math.max(1, totalTime));
    const clamped = rawFractions.map((f) => Math.max(f, MIN_ROW_FRACTION));
    const clampedTotal = clamped.reduce((a, b) => a + b, 0);
    const fractions = clamped.map((f) => f / clampedTotal);

    const rows = legs.map((leg, i) => {
      const innerWidth = (CHART_WIDTH - MARGIN.left - MARGIN.right) * fractions[i];
      const x = scaleLinear()
        .domain([0, Math.max(1, leg.samples[leg.samples.length - 1]?.t ?? 1)])
        .range([0, innerWidth]);

      const altitudes = leg.samples.map((s) => s.altitude).filter((v): v is number => v != null);
      const altMax = max(altitudes) ?? 0;
      const altMin = Math.min(0, ...altitudes);
      const yAlt = scaleLinear().domain([altMin, Math.max(altMax * 1.3, altMin + 1)]).range([innerHeight, 0]);

      return {
        innerWidth,
        fraction: fractions[i],
        x,
        metricLine: line<Sample>()
          .defined((s) => s[metric] != null)
          .x((s) => x(s.t))
          .y((s) => y(s[metric] as number)),
        elevationArea: area<Sample>()
          .defined((s) => s.altitude != null)
          .x((s) => x(s.t))
          .y0(innerHeight)
          .y1((s) => yAlt(s.altitude as number)),
      };
    });
    return { y, innerHeight, rows, ticks: y.ticks(2) };
  }, [legs, metric]);

  if (!legs || !layout) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading streams…</div>;
  }

  function handleMouseMove(legIndex: number, event: React.MouseEvent<SVGSVGElement>) {
    const row = layout!.rows[legIndex];
    const rect = event.currentTarget.getBoundingClientRect();
    const svgWidth = MARGIN.left + row.innerWidth + MARGIN.right;
    const relativeX = ((event.clientX - rect.left) / rect.width) * svgWidth - MARGIN.left;
    const targetT = row.x.invert(relativeX);
    let closest = 0;
    let closestDist = Infinity;
    legs![legIndex].samples.forEach((s, i) => {
      const d = Math.abs(s.t - targetT);
      if (d < closestDist) {
        closestDist = d;
        closest = i;
      }
    });
    setHover({ leg: legIndex, index: closest });
  }

  const activeMetric = METRICS.find((m) => m.key === metric)!;
  const hoveredLeg = hover ? legs[hover.leg] : null;
  const hoveredSample = hover ? hoveredLeg?.samples[hover.index] : null;

  return (
    <div>
      <div style={{ display: "flex", gap: 6, marginBottom: 10 }}>
        {METRICS.map((m) => (
          <button
            key={m.key}
            onClick={() => setMetric(m.key)}
            style={{
              border: "1px solid var(--line)",
              borderRadius: 8,
              padding: "6px 12px",
              fontSize: 13,
              fontWeight: 600,
              background: metric === m.key ? "var(--elev)" : "transparent",
              color: metric === m.key ? "var(--ink)" : "var(--ink3)",
            }}
          >
            {m.label}
          </button>
        ))}
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
        {legs.map((leg, i) => {
          const row = layout.rows[i];
          const svgWidth = MARGIN.left + row.innerWidth + MARGIN.right;
          const legColor = sportColor(leg.activity.sport);
          return (
            <div key={leg.activity.id} style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <div style={{ width: LABEL_COLUMN, flexShrink: 0 }}>
                <span
                  style={{
                    fontSize: 11,
                    fontWeight: 600,
                    padding: "2px 8px",
                    borderRadius: 20,
                    background: legColor,
                    color: "#fff",
                  }}
                >
                  {sportLabel(leg.activity.sport)}
                </span>
                <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", marginTop: 4 }}>
                  {formatDuration(leg.activity.moving_time)}
                </div>
              </div>
              <svg
                width={`${(svgWidth / CHART_WIDTH) * 100}%`}
                viewBox={`0 0 ${svgWidth} ${ROW_HEIGHT}`}
                onMouseMove={(e) => handleMouseMove(i, e)}
                onMouseLeave={() => setHover(null)}
                role="img"
                aria-label={`${sportLabel(leg.activity.sport)} leg stream chart`}
              >
                <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
                  {layout.ticks.map((tick) => (
                    <g key={tick}>
                      <line x1={0} x2={row.innerWidth} y1={layout.y(tick)} y2={layout.y(tick)} stroke="var(--line)" strokeDasharray="3,3" />
                      {/* Tick labels only on the first row: the y-scale is shared. */}
                      {i === 0 && (
                        <text x={-8} y={layout.y(tick)} textAnchor="end" dominantBaseline="middle" fontSize={10} fill="var(--ink3)">
                          {tick}
                        </text>
                      )}
                    </g>
                  ))}
                  <line x1={0} x2={row.innerWidth} y1={layout.innerHeight} y2={layout.innerHeight} stroke="var(--line)" />

                  <path d={row.elevationArea(leg.samples) ?? ""} fill="var(--line)" opacity={0.5} />
                  <path d={row.metricLine(leg.samples) ?? ""} fill="none" stroke={legColor} strokeWidth={1.5} />

                  {hover?.leg === i && hoveredSample && (
                    <g>
                      <line x1={row.x(hoveredSample.t)} x2={row.x(hoveredSample.t)} y1={0} y2={layout.innerHeight} stroke="var(--ink3)" strokeDasharray="2,2" />
                      {hoveredSample[metric] != null && (
                        <circle cx={row.x(hoveredSample.t)} cy={layout.y(hoveredSample[metric] as number)} r={3.5} fill={legColor} />
                      )}
                    </g>
                  )}
                </g>
              </svg>
            </div>
          );
        })}
      </div>

      <div style={{ display: "flex", gap: 18, fontSize: 12, color: "var(--ink2)", marginTop: 6, minHeight: 18 }}>
        {hoveredLeg && hoveredSample && (
          <>
            <span style={{ fontWeight: 600 }}>{sportLabel(hoveredLeg.activity.sport)}</span>
            <span>{formatDuration(Math.round(hoveredSample.t))}</span>
            {hoveredSample[metric] != null && (
              <span>
                {hoveredSample[metric]} {activeMetric.unit}
              </span>
            )}
            {hoveredSample.altitude != null && <span>{Math.round(hoveredSample.altitude)} m</span>}
          </>
        )}
      </div>
    </div>
  );
}
