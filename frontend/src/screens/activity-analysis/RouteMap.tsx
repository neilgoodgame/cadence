import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { getStreams } from "../../api/activities";
import type { Activity } from "../../api/types";
import { RouteMap } from "../../components/RouteMap";

const HEIGHT = 360;

/** Activity-page wrapper around the generic `RouteMap` - fetches this activity's own GPS
 * stream, handles the no-GPS/loading states, and overlays the temp/humidity badge. The
 * generic component (map lifecycle, theme switch, markers) lives in `components/RouteMap`. */
export function ActivityRouteMap({ activity }: { activity: Activity }) {
  const { data } = useQuery({
    queryKey: ["activity-streams-route", activity.id],
    queryFn: () => getStreams(activity.id, ["latlng"], "low"),
    enabled: activity.has_gps,
  });

  const coordinates = useMemo(() => {
    // Every other channel is a flat number[]; latlng alone is [lat, lng] pairs (confirmed
    // against a real response) - StreamsResponse.fields' type covers the common case, so
    // this one channel needs a cast rather than reshaping the type for one exception.
    const latlng = data?.fields.latlng as unknown as [number, number][] | undefined;
    if (!latlng || latlng.length === 0) return null;
    // GeoJSON coordinate order is [lng, lat].
    return latlng.map(([lat, lng]) => [lng, lat] as [number, number]);
  }, [data]);

  if (!activity.has_gps) {
    return (
      <div
        style={{
          height: HEIGHT,
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

  if (!coordinates) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading route…</div>;
  }

  return (
    <div style={{ position: "relative" }}>
      <RouteMap coordinates={coordinates} height={HEIGHT} />
      {activity.avg_air_temp != null && (
        <div
          className="mono"
          style={{
            position: "absolute",
            top: 14,
            right: 14,
            display: "flex",
            alignItems: "center",
            gap: 7,
            background: "var(--card)",
            border: "1px solid var(--line)",
            borderRadius: 8,
            padding: "6px 11px",
            fontSize: 12,
            color: "var(--ink2)",
          }}
        >
          {activity.avg_air_temp.toFixed(1)}°C
          {activity.avg_humidity != null && ` · ${activity.avg_humidity}% RH`}
        </div>
      )}
    </div>
  );
}
