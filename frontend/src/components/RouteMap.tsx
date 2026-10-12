import { useEffect, useRef } from "react";
import * as maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
// maplibre-gl v6's own worker chunk imports a sibling .mjs Vite's plain dependency
// pre-bundling doesn't carry along, so the worker fails on its first import and no vector
// tiles ever load (no visible error - the map just silently never fetches tile data).
// ?worker&url routes it through Vite's worker pipeline instead, which does bundle correctly.
// https://github.com/maplibre/maplibre-gl-js/issues/7339
import maplibreWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";

maplibregl.setWorkerUrl(maplibreWorkerUrl);

// Free Carto vector basemaps - no API key required.
const STYLE_URLS = {
  light: "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json",
  dark: "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json",
};

function currentStyleUrl(): string {
  return document.documentElement.dataset.theme === "day" ? STYLE_URLS.light : STYLE_URLS.dark;
}

const EARTH_RADIUS_KM = 6371.0088;

function haversineKm(a: [number, number], b: [number, number]): number {
  const [lng1, lat1] = a;
  const [lng2, lat2] = b;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const s =
    Math.sin(dLat / 2) ** 2 + Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(s));
}

function totalDistanceKm(coordinates: [number, number][]): number {
  let total = 0;
  for (let i = 1; i < coordinates.length; i++) total += haversineKm(coordinates[i - 1], coordinates[i]);
  return total;
}

/** Walks cumulative distance along `coordinates`, returning a [lng, lat] position every
 * `intervalKm` (interpolated between the two samples straddling each mark). */
function kmMarkerPositions(coordinates: [number, number][], intervalKm: number): [number, number][] {
  const positions: [number, number][] = [];
  let cumulative = 0;
  let nextMark = intervalKm;
  for (let i = 1; i < coordinates.length; i++) {
    const segKm = haversineKm(coordinates[i - 1], coordinates[i]);
    const prevCumulative = cumulative;
    cumulative += segKm;
    while (segKm > 0 && nextMark <= cumulative) {
      const t = (nextMark - prevCumulative) / segKm;
      const [lng1, lat1] = coordinates[i - 1];
      const [lng2, lat2] = coordinates[i];
      positions.push([lng1 + t * (lng2 - lng1), lat1 + t * (lat2 - lat1)]);
      nextMark += intervalKm;
    }
  }
  return positions;
}

/**
 * Generic MapLibre route renderer - owns the map lifecycle, the light/dark basemap swap, and
 * start/finish/km markers. Callers supply already-projected `[lng, lat]` coordinates (GeoJSON
 * order); `ActivityRouteMap` is the activity-page wrapper that fetches and reshapes GPS streams
 * into this shape. See design_handoff_races/README.md §4.2.4 for the km-marker spacing rule.
 */
export function RouteMap({
  coordinates,
  height,
  /** Draws the start as a small dot nested inside the finish marker, for a course whose
   * start and finish are the same point. */
  startFinishLoop = false,
  /** Every 10km, or every 5km for a route under 30km total. */
  kmMarkers = false,
}: {
  coordinates: [number, number][];
  height: number;
  startFinishLoop?: boolean;
  kmMarkers?: boolean;
}) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (coordinates.length === 0 || !containerRef.current) return;

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

    const markers: maplibregl.Marker[] = [];
    markers.push(new maplibregl.Marker({ color: ember, scale: 0.7 }).setLngLat(coordinates.at(-1)!).addTo(map));
    markers.push(
      new maplibregl.Marker({ color: "#2fa66a", scale: startFinishLoop ? 0.35 : 0.7 }).setLngLat(coordinates[0]).addTo(map),
    );

    if (kmMarkers) {
      const totalKm = totalDistanceKm(coordinates);
      const intervalKm = totalKm < 30 ? 5 : 10;
      for (const pos of kmMarkerPositions(coordinates, intervalKm)) {
        const el = document.createElement("div");
        el.style.cssText =
          "width:8px;height:8px;border-radius:50%;background:var(--ink2);border:1.5px solid var(--card);";
        markers.push(new maplibregl.Marker({ element: el }).setLngLat(pos).addTo(map));
      }
    }

    // style.load fires on initial load and again after every setStyle (theme switch), and
    // setStyle wipes custom sources/layers - so the route is (re-)added here.
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
      markers.forEach((m) => m.remove());
      map.remove();
    };
  }, [coordinates, startFinishLoop, kmMarkers]);

  return (
    <div style={{ position: "relative", height, borderRadius: 12, border: "1px solid var(--line)", overflow: "hidden" }}>
      <div ref={containerRef} role="img" aria-label="Route map" style={{ width: "100%", height: "100%" }} />
    </div>
  );
}
