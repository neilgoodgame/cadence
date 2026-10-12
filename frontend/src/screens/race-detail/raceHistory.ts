import type { Race } from "../../api/types";
import { parseDuration } from "../../lib/format";

/** Same bucket as PastRacesTable's PB grouping - round to the nearest 0.1 km so e.g. 21.1 and
 * 21.0975 (half marathon) count as the same distance. */
function distanceKey(km: number): number {
  return Math.round(km * 10) / 10;
}

export function resultSeconds(race: Race): number | null {
  return race.result_time ? parseDuration(race.result_time) : null;
}

/** Every other past, same-sport race within 1% of `distanceKm` that has a result - sorted
 * newest first. Excludes `excludeRaceId` (the race whose own card this feeds). */
export function historyAtDistance(races: Race[], sport: string, distanceKm: number, excludeRaceId: string): Race[] {
  return races
    .filter(
      (r) =>
        r.id !== excludeRaceId &&
        r.sport === sport &&
        r.distance_km != null &&
        Math.abs(r.distance_km - distanceKm) / distanceKm <= 0.01 &&
        resultSeconds(r) != null,
    )
    .sort((a, b) => b.date.localeCompare(a.date));
}

/** The fastest result among `races` at this distance, optionally including `includeRace`
 * itself (for a completed race checking whether it IS the PB). */
export function personalBestSeconds(races: Race[], includeRace: Race | null): number | null {
  const candidates = includeRace ? [...races, includeRace] : races;
  let best: number | null = null;
  for (const r of candidates) {
    const s = resultSeconds(r);
    if (s != null && (best == null || s < best)) best = s;
  }
  return best;
}

export { distanceKey };
