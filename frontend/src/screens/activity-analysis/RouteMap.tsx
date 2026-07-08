import { useEffect, useMemo, useRef } from "react";
import { useQuery } from "@tanstack/react-query";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { getStreams } from "../../api/activities";
import type { Activity } from "../../api/types";

const HEIGHT = 360;

// Free Carto vector basemaps - no API key required.
const STYLE_URLS = {
  light: "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json",
  dark: "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json",
};

function currentStyleUrl(): string {
  return document.documentElement.dataset.theme === "day" ? STYLE_URLS.light : STYLE_URLS.dark;
}

export function RouteMap({ activity }: { activity: Activity }) {
  const containerRef = useRef<HTMLDivElement>(null);

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

  useEffect(() => {
    if (!coordinates || !containerRef.current) return;

    const ember = getComputedStyle(document.documentElement).getPropertyValue("--ember").trim() || "#e0442e";

    const bounds = coordinates.reduce(
      (acc, coord) => acc.extend(coord),
      new maplibregl.LngLatBounds(coordinates[0], coordinates[0]),
    );

    const map = new maplibregl.Map({
      container: containerRef.current,
      style: currentStyleUrl(),
      bounds,
      fitBoundsOptions: { padding: 32 },
      // Plain scroll keeps scrolling the page; Ctrl/Cmd+scroll zooms the map.
      cooperativeGestures: true,
      attributionControl: { compact: true },
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");

    new maplibregl.Marker({ color: "#2fa66a", scale: 0.7 }).setLngLat(coordinates[0]).addTo(map);
    new maplibregl.Marker({ color: ember, scale: 0.7 }).setLngLat(coordinates.at(-1)!).addTo(map);

    // style.load fires on initial load and again after every setStyle (theme switch),
    // and setStyle wipes custom sources/layers - so the route is (re-)added here.
    map.on("style.load", () => {
      map.addSource("route", {
        type: "geojson",
        data: { type: "Feature", geometry: { type: "LineString", coordinates }, properties: {} },
      });
      map.addLayer({
        id: "route-line",
        type: "line",
        source: "route",
        layout: { "line-cap": "round", "line-join": "round" },
        paint: { "line-color": ember, "line-width": 3 },
      });
    });

    const themeObserver = new MutationObserver(() => map.setStyle(currentStyleUrl()));
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });

    return () => {
      themeObserver.disconnect();
      map.remove();
    };
  }, [coordinates]);

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
    <div
      ref={containerRef}
      role="img"
      aria-label="Route map"
      style={{ height: HEIGHT, borderRadius: 12, border: "1px solid var(--line)", overflow: "hidden" }}
    />
  );
}
