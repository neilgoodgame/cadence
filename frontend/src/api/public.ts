import { apiFetch } from "./client";
import { toQueryString } from "./activities";
import { ApiError, type PublicRace, type RaceSplits, type RaceWeather, type SplitsMode } from "./types";

// Unauthenticated reads of a race its owner has made public - `anonymous` skips the Bearer token
// and the refresh-on-401 retry, so these behave the same whether or not a viewer is logged in.
// All 404 unless the race is public.

/** react-query `retry` for these: a 404 means "not shared" (or gone) - never worth retrying. */
export function retryUnlessNotFound(failureCount: number, error: Error): boolean {
  return !(error instanceof ApiError && error.status === 404) && failureCount < 3;
}

export function getPublicRace(id: string): Promise<PublicRace> {
  return apiFetch<PublicRace>(`/v1/public/races/${id}`, { anonymous: true });
}

export function getPublicRaceSplits(id: string, mode: SplitsMode): Promise<RaceSplits> {
  return apiFetch<RaceSplits>(`/v1/public/races/${id}/splits${toQueryString({ mode })}`, { anonymous: true });
}

export function getPublicRaceWeather(id: string): Promise<RaceWeather> {
  return apiFetch<RaceWeather>(`/v1/public/races/${id}/weather`, { anonymous: true });
}

export function publicRaceUrl(id: string): string {
  return `${window.location.origin}/public/races/${id}`;
}
