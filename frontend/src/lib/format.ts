/** Seconds -> "H:MM:SS", or "MM:SS" under an hour. */
export function formatDuration(totalSeconds: number): string {
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = Math.floor(totalSeconds % 60);
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

/** Seconds per km -> "M:SS /km". */
export function formatPace(secondsPerKm: number): string {
  const minutes = Math.floor(secondsPerKm / 60);
  const seconds = Math.round(secondsPerKm % 60);
  return `${minutes}:${String(seconds).padStart(2, "0")} /km`;
}

export function formatDate(isoDate: string): string {
  return new Date(isoDate).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
}

/** Compact "duration · distance · power|pace" summary for a linked-activity row, e.g.
 * "1:15:20 · 35.0 km · 231 W" (power) or "1:05:02 · 13.0 km · 4:02 /km" (pace, when no power). */
export function formatKeyMetric(activity: { moving_time: number; distance_km: number; avg_power: number | null }): string {
  const parts = [formatDuration(activity.moving_time), `${activity.distance_km.toFixed(1)} km`];
  if (activity.avg_power != null) {
    parts.push(`${Math.round(activity.avg_power)} W`);
  } else if (activity.distance_km > 0) {
    parts.push(formatPace(activity.moving_time / activity.distance_km));
  }
  return parts.join(" · ");
}

export function formatDateTime(isoDateTime: string): string {
  return new Date(isoDateTime).toLocaleString(undefined, {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}
