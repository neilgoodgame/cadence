import type { StreamsResponse } from "../api/types";

function xmlEscape(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** Builds a GPX 1.1 track from a streams response - lat/lng (required), altitude and heart
 * rate (via the Garmin TrackPointExtension namespace, widely supported by import tools) when
 * present. Callers should only offer this for activities with has_gps - every point needs a
 * coordinate, so there's no meaningful export for an indoor/trainer session.
 */
export function buildGpx(name: string, startDateIso: string, streams: StreamsResponse): string {
  const latlng = streams.fields.latlng as unknown as [number, number][] | undefined;
  const time = streams.fields.time;
  if (!latlng || !time) return "";

  const startMs = new Date(startDateIso).getTime();
  const altitude = streams.fields.altitude;
  const heartrate = streams.fields.heartrate;

  const points = latlng
    .map(([lat, lng], i) => {
      if (lat == null || lng == null) return "";
      const t = new Date(startMs + (time[i] ?? 0) * 1000).toISOString();
      const ele = altitude?.[i] != null ? `<ele>${altitude[i]}</ele>` : "";
      const hr = heartrate?.[i] != null
        ? `<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>${heartrate[i]}</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>`
        : "";
      return `<trkpt lat="${lat}" lon="${lng}">${ele}<time>${t}</time>${hr}</trkpt>`;
    })
    .filter(Boolean)
    .join("");

  return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Cadence" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
<trk><name>${xmlEscape(name)}</name><trkseg>${points}</trkseg></trk>
</gpx>`;
}

export function downloadGpx(filename: string, gpx: string): void {
  const blob = new Blob([gpx], { type: "application/gpx+xml" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
