import { useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { extent, max } from "d3-array";
import { scaleLinear } from "d3-scale";
import { area, line } from "d3-shape";
import { getStreams } from "../../api/activities";
import type { Activity } from "../../api/types";

type Metric = "power" | "heartrate" | "cadence";

const METRICS: { key: Metric; label: string; color: string; unit: string }[] = [
  { key: "power", label: "Power", color: "#2dd4bf", unit: "w" },
  { key: "heartrate", label: "Heart Rate", color: "#e0442e", unit: "bpm" },
  { key: "cadence", label: "Cadence", color: "#3d7fd6", unit: "rpm" },
];

const WIDTH = 900;
const HEIGHT = 230;
const MARGIN = { top: 10, right: 16, bottom: 24, left: 40 };

interface Sample {
  x: number;
  power: number | null;
  heartrate: number | null;
  cadence: number | null;
  altitude: number | null;
}

export function StreamChart({ activity }: { activity: Activity }) {
  const [metric, setMetric] = useState<Metric>(activity.avg_power ? "power" : "heartrate");
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);

  const { data } = useQuery({
    queryKey: ["activity-streams-chart", activity.id],
    queryFn: () => getStreams(activity.id, ["time", "power", "heartrate", "cadence", "altitude", "distance"], "medium"),
  });

  const samples: Sample[] = useMemo(() => {
    if (!data) return [];
    const fields = data.fields;
    const length = fields.time?.length ?? 0;
    const useDistance = activity.has_gps && fields.distance;
    return Array.from({ length }, (_, i) => ({
      x: useDistance ? (fields.distance![i] ?? 0) : (fields.time![i] ?? 0),
      power: fields.power?.[i] ?? null,
      heartrate: fields.heartrate?.[i] ?? null,
      cadence: fields.cadence?.[i] ?? null,
      altitude: fields.altitude?.[i] ?? null,
    }));
  }, [data, activity.has_gps]);

  const chart = useMemo(() => {
    if (samples.length === 0) return null;
    const innerWidth = WIDTH - MARGIN.left - MARGIN.right;
    const innerHeight = HEIGHT - MARGIN.top - MARGIN.bottom;

    const [xMin, xMax] = extent(samples, (s) => s.x);
    const x = scaleLinear()
      .domain([xMin ?? 0, xMax ?? 1])
      .range([0, innerWidth]);

    const metricValues = samples.map((s) => s[metric]).filter((v): v is number => v != null);
    const yMax = max(metricValues) ?? 1;
    const y = scaleLinear().domain([0, yMax * 1.1]).range([innerHeight, 0]);

    const altitudeValues = samples.map((s) => s.altitude).filter((v): v is number => v != null);
    const altMax = max(altitudeValues) ?? 0;
    const altMin = Math.min(0, ...altitudeValues);
    const yAlt = scaleLinear().domain([altMin, Math.max(altMax * 1.3, altMin + 1)]).range([innerHeight, 0]);

    const metricLine = line<Sample>()
      .defined((s) => s[metric] != null)
      .x((s) => x(s.x))
      .y((s) => y(s[metric] as number));

    const elevationArea = area<Sample>()
      .defined((s) => s.altitude != null)
      .x((s) => x(s.x))
      .y0(innerHeight)
      .y1((s) => yAlt(s.altitude as number));

    return { innerWidth, innerHeight, x, y, metricLine, elevationArea, ticks: y.ticks(4) };
  }, [samples, metric]);

  function handleMouseMove(event: React.MouseEvent<SVGSVGElement>) {
    if (!chart || !svgRef.current) return;
    const rect = svgRef.current.getBoundingClientRect();
    const relativeX = ((event.clientX - rect.left) / rect.width) * WIDTH - MARGIN.left;
    const targetX = chart.x.invert(relativeX);
    let closest = 0;
    let closestDist = Infinity;
    samples.forEach((s, i) => {
      const d = Math.abs(s.x - targetX);
      if (d < closestDist) {
        closestDist = d;
        closest = i;
      }
    });
    setHoverIndex(closest);
  }

  if (!chart) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading stream…</div>;
  }

  const activeMetric = METRICS.find((m) => m.key === metric)!;
  const hovered = hoverIndex != null ? samples[hoverIndex] : null;

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

      <svg
        ref={svgRef}
        width="100%"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        onMouseMove={handleMouseMove}
        onMouseLeave={() => setHoverIndex(null)}
        role="img"
        aria-label="Activity stream chart"
      >
        <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
          {chart.ticks.map((tick) => (
            <g key={tick}>
              <line x1={0} x2={chart.innerWidth} y1={chart.y(tick)} y2={chart.y(tick)} stroke="var(--line)" strokeDasharray="3,3" />
              <text x={-8} y={chart.y(tick)} textAnchor="end" dominantBaseline="middle" fontSize={10} fill="var(--ink3)">
                {tick}
              </text>
            </g>
          ))}

          <path d={chart.elevationArea(samples) ?? ""} fill="var(--line)" opacity={0.5} />
          <path d={chart.metricLine(samples) ?? ""} fill="none" stroke={activeMetric.color} strokeWidth={2} />

          {hovered && (
            <g>
              <line x1={chart.x(hovered.x)} x2={chart.x(hovered.x)} y1={0} y2={chart.innerHeight} stroke="var(--ink3)" strokeDasharray="2,2" />
              <circle cx={chart.x(hovered.x)} cy={chart.y((hovered[metric] as number) ?? 0)} r={4} fill={activeMetric.color} />
            </g>
          )}
        </g>
      </svg>

      {hovered && (
        <div style={{ display: "flex", gap: 18, fontSize: 12, color: "var(--ink2)" }}>
          <span>{activity.has_gps ? `${hovered.x.toFixed(1)} km` : `${Math.round(hovered.x)}s`}</span>
          {hovered.power != null && <span>{hovered.power} w</span>}
          {hovered.heartrate != null && <span>{hovered.heartrate} bpm</span>}
          {hovered.cadence != null && <span>{hovered.cadence} rpm</span>}
          {hovered.altitude != null && <span>{Math.round(hovered.altitude)} m</span>}
        </div>
      )}
    </div>
  );
}
