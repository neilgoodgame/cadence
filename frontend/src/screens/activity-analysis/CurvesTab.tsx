import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { max, min } from "d3-array";
import { scaleLinear, scaleLog } from "d3-scale";
import { area, curveMonotoneX, line } from "d3-shape";
import { getCurves } from "../../api/activities";

const WIDTH = 420;
const HEIGHT = 180;
const MARGIN = { top: 10, right: 4, bottom: 22, left: 30 };
const TICK_SECONDS = [5, 60, 300, 1200, 3600];
const TICK_LABELS = ["5s", "1m", "5m", "20m", "1h"];

/** "1h31" / "45m" - matches the tick-label style (5s, 1m, 20m), not the H:MM:SS clock format used elsewhere. */
function formatEndDuration(totalSeconds: number): string {
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  return hours > 0 ? `${hours}h${String(minutes).padStart(2, "0")}` : `${minutes}m`;
}

function DurationCurveChart({
  activityId,
  metric,
  label,
  color,
  fill,
  unit,
}: {
  activityId: string;
  metric: "power" | "heartrate";
  label: string;
  color: string;
  fill: string;
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

    const minSeconds = min(points, (p) => p.seconds) ?? 5;
    const maxSeconds = max(points, (p) => p.seconds) ?? 3600;
    const x = scaleLog().domain([minSeconds, maxSeconds]).range([0, innerWidth]);
    const y = scaleLinear()
      .domain([(min(points, (p) => p.value) ?? 0) * 0.9, (max(points, (p) => p.value) ?? 1) * 1.05])
      .range([innerHeight, 0]);

    const curveLine = line<{ seconds: number; value: number }>()
      .x((p) => x(p.seconds))
      .y((p) => y(p.value))
      .curve(curveMonotoneX);
    const curveArea = area<{ seconds: number; value: number }>()
      .x((p) => x(p.seconds))
      .y0(innerHeight)
      .y1((p) => y(p.value))
      .curve(curveMonotoneX);

    const last = points.at(-1)!;
    const extendsBeyondHour = last.seconds > 3600;
    // The final standard tick (1h) is dropped in favor of the curve's actual endpoint
    // duration when it extends past it - showing both "1h" and "1h31" right next to each
    // other is redundant, and the real duration is the more useful number.
    const standardTicks = (extendsBeyondHour ? TICK_SECONDS.slice(0, -1) : TICK_SECONDS).filter(
      (t) => t >= minSeconds && t <= maxSeconds,
    );

    // Percent-of-container helpers for the HTML overlays below (dot, endpoint label,
    // "BEYOND 1H" label, ticks) - kept as plain HTML rather than SVG <text>/<circle>
    // because the chart SVG below uses preserveAspectRatio="none" so its curve fills the
    // card edge-to-edge; that same non-uniform scaling would stretch SVG text into
    // squashed, hard-to-read glyphs and turn circles into ellipses. Geometry (the line/
    // area/hatch fill) stays inside the SVG since stretching *those* is exactly the point.
    const xPct = (seconds: number) => ((x(seconds) + MARGIN.left) / WIDTH) * 100;
    const yPct = (value: number) => ((y(value) + MARGIN.top) / HEIGHT) * 100;
    // Drop a tick sitting right on the domain edge - it'd otherwise collide with the
    // curve's own start/end value labels.
    const yTicks = y.ticks(4).filter((t) => t > y.domain()[0] && t < y.domain()[1]);

    return {
      points,
      innerWidth,
      innerHeight,
      x,
      y,
      curveLine,
      curveArea,
      standardTicks,
      yTicks,
      last,
      extendsBeyondHour,
      beyondHourX: x(3600),
      xPct,
      yPct,
    };
  }, [data]);

  if (!chart) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading…</div>;
  }

  const patternId = `beyond-hatch-${metric}`;

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "20px 22px" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 18 }}>
        <div className="mono" style={{ fontSize: 11, letterSpacing: "0.08em", color }}>
          {label.toUpperCase()} DURATION CURVE
        </div>
        <div className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>
          best avg {unit}
        </div>
      </div>

      <div style={{ position: "relative", height: HEIGHT }}>
        <svg
          width="100%"
          height="100%"
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          preserveAspectRatio="none"
          role="img"
          aria-label={`${label} duration curve`}
        >
          <defs>
            <pattern id={patternId} patternUnits="userSpaceOnUse" width="8" height="8" patternTransform="rotate(45)">
              <rect width="8" height="8" fill="transparent" />
              <line x1="0" y1="0" x2="0" y2="8" stroke={color} strokeWidth="4" strokeOpacity={0.12} />
            </pattern>
          </defs>
          <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
            {chart.yTicks.map((t) => (
              <line
                key={t}
                x1={0}
                x2={chart.innerWidth}
                y1={chart.y(t)}
                y2={chart.y(t)}
                stroke="var(--line)"
                strokeDasharray="3,3"
                vectorEffect="non-scaling-stroke"
              />
            ))}
            {chart.extendsBeyondHour && (
              <>
                <rect
                  x={chart.beyondHourX}
                  y={0}
                  width={Math.max(0, chart.innerWidth - chart.beyondHourX)}
                  height={chart.innerHeight}
                  fill={`url(#${patternId})`}
                />
                <line
                  x1={chart.beyondHourX}
                  x2={chart.beyondHourX}
                  y1={0}
                  y2={chart.innerHeight}
                  stroke={color}
                  strokeOpacity={0.45}
                  strokeDasharray="3,3"
                  vectorEffect="non-scaling-stroke"
                />
              </>
            )}
            <path d={chart.curveArea(chart.points) ?? ""} fill={fill} />
            <path d={chart.curveLine(chart.points) ?? ""} fill="none" stroke={color} strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
          </g>
        </svg>

        {chart.yTicks.map((t) => (
          <div
            key={t}
            className="mono"
            style={{
              position: "absolute",
              top: `${chart.yPct(t)}%`,
              left: 0,
              width: MARGIN.left - 8,
              textAlign: "right",
              transform: "translateY(-50%)",
              fontSize: 10,
              color: "var(--ink3)",
            }}
          >
            {t}
          </div>
        ))}

        {chart.extendsBeyondHour && (
          <div
            className="mono"
            style={{
              position: "absolute",
              top: MARGIN.top - 1,
              left: `${chart.xPct(3600)}%`,
              transform: "translateX(5px)",
              fontSize: 9,
              letterSpacing: "0.04em",
              color,
              opacity: 0.8,
            }}
          >
            BEYOND 1H
          </div>
        )}

        <div
          style={{
            position: "absolute",
            top: `${chart.yPct(chart.last.value)}%`,
            left: `${chart.xPct(chart.last.seconds)}%`,
            width: 9,
            height: 9,
            borderRadius: "50%",
            background: color,
            border: "2px solid var(--card)",
            transform: "translate(-50%, -50%)",
          }}
        />
        <div
          className="mono"
          style={{
            position: "absolute",
            top: `${chart.yPct(chart.last.value)}%`,
            left: `${chart.xPct(chart.last.seconds)}%`,
            transform: "translate(calc(-100% - 8px), calc(-50% - 2px))",
            fontSize: 11,
            fontWeight: 600,
            color,
            whiteSpace: "nowrap",
          }}
        >
          {Math.round(chart.last.value)}
          {unit}
        </div>
      </div>

      <div style={{ position: "relative", height: 14, fontFamily: "'JetBrains Mono', monospace", fontSize: 10, color: "var(--ink3)", marginTop: 8 }}>
        {chart.standardTicks.map((t) => (
          <span
            key={t}
            style={{
              position: "absolute",
              left: `${chart.xPct(t)}%`,
              transform: t === chart.standardTicks[0] ? undefined : "translateX(-50%)",
            }}
          >
            {TICK_LABELS[TICK_SECONDS.indexOf(t)]}
          </span>
        ))}
        <span
          style={{
            position: "absolute",
            left: `${chart.xPct(chart.last.seconds)}%`,
            transform: "translateX(-100%)",
            color: "var(--ink)",
            fontWeight: 600,
          }}
        >
          {formatEndDuration(chart.last.seconds)}
        </span>
      </div>

      {chart.extendsBeyondHour && (
        <div style={{ fontSize: 11, color: "var(--ink3)", marginTop: 9, lineHeight: 1.4 }}>
          Extended to full activity length{" "}
          <span className="mono" style={{ color: "var(--ink2)" }}>
            {formatEndDuration(chart.last.seconds)}
          </span>{" "}
          — the endpoint is your whole-activity average.
        </div>
      )}
    </div>
  );
}

export function CurvesTab({ activityId }: { activityId: string }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
      <DurationCurveChart
        activityId={activityId}
        metric="power"
        label="Power"
        color="var(--ember)"
        fill="var(--ember-soft)"
        unit="w"
      />
      <DurationCurveChart
        activityId={activityId}
        metric="heartrate"
        label="Heart Rate"
        color="#e0442e"
        fill="rgba(224, 68, 46, 0.12)"
        unit="bpm"
      />
    </div>
  );
}
