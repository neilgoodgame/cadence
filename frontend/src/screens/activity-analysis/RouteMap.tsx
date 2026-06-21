import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { extent } from "d3-array";
import { scaleLinear } from "d3-scale";
import { getStreams } from "../../api/activities";
import type { Activity } from "../../api/types";

const SIZE = 360;
const PADDING = 16;

export function RouteMap({ activity }: { activity: Activity }) {
  const { data } = useQuery({
    queryKey: ["activity-streams-route", activity.id],
    queryFn: () => getStreams(activity.id, ["latlng"], "low"),
    enabled: activity.has_gps,
  });

  const path = useMemo(() => {
    // Every other channel is a flat number[]; latlng alone is [lat, lng] pairs (confirmed
    // against a real response) - StreamsResponse.fields' type covers the common case, so
    // this one channel needs a cast rather than reshaping the type for one exception.
    const latlng = data?.fields.latlng as unknown as [number, number][] | undefined;
    if (!latlng || latlng.length === 0) return null;

    const lats = latlng.map((p) => p[0]);
    const lngs = latlng.map((p) => p[1]);
    const [latMin, latMax] = extent(lats) as [number, number];
    const [lngMin, lngMax] = extent(lngs) as [number, number];

    // Equirectangular-ish: scale lng by cos(latitude) so the trace isn't stretched.
    const aspectCorrection = Math.cos((((latMin + latMax) / 2) * Math.PI) / 180);
    const lngScale = scaleLinear()
      .domain([lngMin, lngMax])
      .range([PADDING, SIZE - PADDING]);
    const latScale = scaleLinear()
      .domain([latMin, latMax])
      .range([SIZE - PADDING, PADDING]);

    const points = latlng.map(([lat, lng]) => {
      const x = PADDING + (lngScale(lng) - PADDING) * aspectCorrection;
      const y = latScale(lat);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    });
    return { points: points.join(" "), start: points[0], end: points.at(-1) };
  }, [data]);

  if (!activity.has_gps) {
    return (
      <div
        style={{
          height: SIZE,
          borderRadius: 12,
          border: "1px solid var(--line)",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          flexDirection: "column",
          gap: 6,
          color: "var(--ink3)",
          fontSize: 13,
        }}
      >
        <div>Indoor activity · no GPS</div>
        <div style={{ fontSize: 11 }}>Distance is measured from the {activity.distance_source}.</div>
      </div>
    );
  }

  if (!path) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading route…</div>;
  }

  const [startX, startY] = path.start!.split(",").map(Number);
  const [endX, endY] = path.end!.split(",").map(Number);

  return (
    <svg width="100%" viewBox={`0 0 ${SIZE} ${SIZE}`} role="img" aria-label="Route map">
      <polyline points={path.points} fill="none" stroke="var(--ember)" strokeWidth={2} strokeLinejoin="round" />
      <circle cx={startX} cy={startY} r={5} fill="#2fa66a" />
      <circle cx={endX} cy={endY} r={5} fill="var(--ember)" />
    </svg>
  );
}
