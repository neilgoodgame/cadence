import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { max, min } from "d3-array";
import { scaleLinear, scaleLog } from "d3-scale";
import { line } from "d3-shape";
import { getCurves } from "../../api/activities";

const WIDTH = 420;
const HEIGHT = 200;
const MARGIN = { top: 10, right: 16, bottom: 24, left: 36 };
const TICK_SECONDS = [5, 60, 300, 1200, 3600];
const TICK_LABELS = ["5s", "1m", "5m", "20m", "1h"];

function DurationCurveChart({
  activityId,
  metric,
  label,
  color,
  unit,
}: {
  activityId: string;
  metric: "power" | "heartrate";
  label: string;
  color: string;
  unit: string;
}) {
  const { data } = useQuery({
    queryKey: ["activity-curve", activityId, metric],
    queryFn: () => getCurves(activityId, metric),
  });

  const chart = useMemo(() => {
    if (!data) return null;
    const points = Object.entries(data.points)
      .map(([seconds, value]) => ({ seconds: Number(seconds), value }))
      .sort((a, b) => a.seconds - b.seconds);
    if (points.length === 0) return null;

    const innerWidth = WIDTH - MARGIN.left - MARGIN.right;
    const innerHeight = HEIGHT - MARGIN.top - MARGIN.bottom;

    // Domain bounds come from the actual data, not a hardcoded "every curve starts at 5s" -
    // the backend computes a shorter window list for heartrate (starts at 60s; HR lags
    // effort too much for a 5/15/30s peak to mean anything) than power (starts at 5s), so a
    // fixed [5, ...] domain would draw HR's axis starting well before its first real point.
    const minSeconds = min(points, (p) => p.seconds) ?? 5;
    const maxSeconds = max(points, (p) => p.seconds) ?? 3600;
    const x = scaleLog().domain([minSeconds, maxSeconds]).range([0, innerWidth]);
    const y = scaleLinear()
      .domain([(min(points, (p) => p.value) ?? 0) * 0.9, (max(points, (p) => p.value) ?? 1) * 1.05])
      .range([innerHeight, 0]);

    const curveLine = line<{ seconds: number; value: number }>()
      .x((p) => x(p.seconds))
      .y((p) => y(p.value));

    const ticks = TICK_SECONDS.filter((t) => t >= minSeconds && t <= maxSeconds);
    const last = points.at(-1)!;
    const extendsBeyondHour = last.seconds > 3600;

    return { points, innerWidth, innerHeight, x, y, curveLine, ticks, last, extendsBeyondHour };
  }, [data]);

  if (!chart) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading…</div>;
  }

  return (
    <div>
      <div className="mono" style={{ fontSize: 11, color, fontWeight: 600, letterSpacing: "0.06em", marginBottom: 2 }}>
        {label.toUpperCase()} DURATION CURVE
      </div>
      <div style={{ fontSize: 12, color: "var(--ink3)", marginBottom: 10 }}>best average {unit}</div>

      <svg width="100%" viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={`${label} duration curve`}>
        <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
          {chart.ticks.map((t) => (
            <text key={t} x={chart.x(t)} y={chart.innerHeight + 16} textAnchor="middle" fontSize={10} fill="var(--ink3)">
              {TICK_LABELS[TICK_SECONDS.indexOf(t)]}
            </text>
          ))}
          <path d={chart.curveLine(chart.points) ?? ""} fill="none" stroke={color} strokeWidth={2} />
          <circle cx={chart.x(chart.last.seconds)} cy={chart.y(chart.last.value)} r={4} fill={color} />
          <text x={chart.x(chart.last.seconds) + 6} y={chart.y(chart.last.value) - 6} fontSize={11} fill={color}>
            {Math.round(chart.last.value)}{unit}
          </text>
        </g>
      </svg>
      {chart.extendsBeyondHour && (
        <div style={{ fontSize: 11, color: "var(--ink3)" }}>
          Extended to the full activity length - the endpoint is the whole-activity average.
        </div>
      )}
    </div>
  );
}

export function CurvesTab({ activityId }: { activityId: string }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 24 }}>
      <DurationCurveChart activityId={activityId} metric="power" label="Power" color="var(--ember)" unit="w" />
      <DurationCurveChart activityId={activityId} metric="heartrate" label="Heart Rate" color="#e0442e" unit="bpm" />
    </div>
  );
}
