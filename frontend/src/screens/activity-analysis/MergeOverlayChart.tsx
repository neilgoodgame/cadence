import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { extent, max } from "d3-array";
import { scaleLinear } from "d3-scale";
import { line } from "d3-shape";
import { getStreams } from "../../api/activities";
import type { Activity } from "../../api/types";

type Metric = "power" | "heartrate" | "cadence";

const METRICS: { key: Metric; label: string }[] = [
  { key: "power", label: "Power" },
  { key: "heartrate", label: "Heart Rate" },
  { key: "cadence", label: "Cadence" },
];

const TARGET_COLOR = "#2dd4bf";
const SOURCE_COLOR = "#e0442e";

const WIDTH = 560;
const HEIGHT = 160;
const MARGIN = { top: 8, right: 10, bottom: 20, left: 36 };

interface Point {
  x: number;
  v: number;
}

function toPoints(fields: Record<string, (number | null)[]>, metric: Metric, offsetSeconds: number): Point[] {
  const time = fields.time ?? [];
  const values = fields[metric] ?? [];
  const points: Point[] = [];
  for (let i = 0; i < time.length; i++) {
    const t = time[i];
    const v = values[i];
    if (t != null && v != null) points.push({ x: t + offsetSeconds, v });
  }
  return points;
}

/** Overlay of both recordings' streams on the target's own timeline (the source shifted by
 * `offsetSeconds`, matching POST /v1/activities/{id}/merge's own convention: positive means the
 * source started that many seconds after the target). Lets the athlete see at a glance whether
 * the resolved/overridden offset actually lines up the two recordings' effort shape. */
export function MergeOverlayChart({
  target,
  source,
  offsetSeconds,
}: {
  target: Activity;
  source: Activity;
  offsetSeconds: number;
}) {
  const [metric, setMetric] = useState<Metric>(target.avg_power || source.avg_power ? "power" : "heartrate");

  const targetStreams = useQuery({
    queryKey: ["activity-streams-merge", target.id],
    queryFn: () => getStreams(target.id, ["time", "power", "heartrate", "cadence"], "medium"),
  });
  const sourceStreams = useQuery({
    queryKey: ["activity-streams-merge", source.id],
    queryFn: () => getStreams(source.id, ["time", "power", "heartrate", "cadence"], "medium"),
  });

  const chart = useMemo(() => {
    if (!targetStreams.data || !sourceStreams.data) return null;
    const targetPoints = toPoints(targetStreams.data.fields, metric, 0);
    const sourcePoints = toPoints(sourceStreams.data.fields, metric, offsetSeconds);
    if (targetPoints.length === 0 && sourcePoints.length === 0) return null;

    const innerWidth = WIDTH - MARGIN.left - MARGIN.right;
    const innerHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
    const allPoints = [...targetPoints, ...sourcePoints];
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
      targetPath: targetPoints.length > 0 ? lineFn(targetPoints) : null,
      sourcePath: sourcePoints.length > 0 ? lineFn(sourcePoints) : null,
      ticks: y.ticks(3),
      yAt: y,
    };
  }, [targetStreams.data, sourceStreams.data, metric, offsetSeconds]);

  const availableMetrics = METRICS.filter(
    (m) => m.key === "power" ? target.avg_power != null || source.avg_power != null
      : m.key === "heartrate" ? target.avg_hr != null || source.avg_hr != null
      : target.avg_cadence != null || source.avg_cadence != null,
  );

  return (
    <div>
      <div style={{ display: "flex", gap: 6, marginBottom: 8 }}>
        {availableMetrics.map((m) => (
          <button
            key={m.key}
            onClick={() => setMetric(m.key)}
            style={{
              border: "1px solid var(--line)",
              borderRadius: 6,
              padding: "3px 8px",
              fontSize: 11,
              fontWeight: 600,
              background: metric === m.key ? "var(--elev)" : "transparent",
              color: metric === m.key ? "var(--ink)" : "var(--ink3)",
            }}
          >
            {m.label}
          </button>
        ))}
        <div style={{ marginLeft: "auto", display: "flex", gap: 10, fontSize: 11, color: "var(--ink3)" }}>
          <span><span style={{ color: TARGET_COLOR }}>●</span> Target</span>
          <span><span style={{ color: SOURCE_COLOR }}>●</span> Source (offset {offsetSeconds}s)</span>
        </div>
      </div>

      {!chart ? (
        <div style={{ color: "var(--ink3)", fontSize: 12, padding: "20px 0" }}>
          {targetStreams.isLoading || sourceStreams.isLoading ? "Loading streams…" : "No overlapping data for this metric."}
        </div>
      ) : (
        <svg width="100%" viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label="Merge alignment overlay chart">
          <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
            {chart.ticks.map((tick) => (
              <g key={tick}>
                <line x1={0} x2={chart.innerWidth} y1={chart.yAt(tick)} y2={chart.yAt(tick)} stroke="var(--line)" strokeDasharray="3,3" />
                <text x={-6} y={chart.yAt(tick)} textAnchor="end" dominantBaseline="middle" fontSize={9} fill="var(--ink3)">
                  {tick}
                </text>
              </g>
            ))}
            {chart.targetPath && <path d={chart.targetPath} fill="none" stroke={TARGET_COLOR} strokeWidth={1.5} />}
            {chart.sourcePath && <path d={chart.sourcePath} fill="none" stroke={SOURCE_COLOR} strokeWidth={1.5} opacity={0.85} />}
          </g>
        </svg>
      )}
    </div>
  );
}
