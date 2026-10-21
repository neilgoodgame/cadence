import { useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getPublicRace, getPublicRaceSplits, retryUnlessNotFound } from "../api/public";
import { PublicPage } from "../components/PublicPage";
import { daysUntil } from "../lib/dates";
import { PublicCourseCard } from "./race-detail/CourseCard";
import { GoalResultCard } from "./race-detail/GoalResultCard";
import { SplitsCard } from "./race-detail/SplitsCard";
import { WeatherCard } from "./race-detail/WeatherCard";

/** Read-only, login-free view of a race its owner has made public (the Share button on the race
 * page) - for friends and family: the result against the goal, splits (laps, per-km, per-5km and
 * by course section), the course and the race-day forecast. None of the race page's training
 * side (block, form, durability, pacing plan, notes, comments). Only ever calls the
 * unauthenticated /v1/public endpoints. */
export function PublicRaceScreen() {
  const { id } = useParams<{ id: string }>();
  const { data: race, isLoading, error } = useQuery({
    queryKey: ["public-race", id],
    queryFn: () => getPublicRace(id!),
    enabled: !!id,
    retry: retryUnlessNotFound,
  });
  // Same 5 km splits the race page fetches for GoalResultCard's first/second-half line.
  const { data: splits } = useQuery({
    queryKey: ["public-race-splits", id, "5"],
    queryFn: () => getPublicRaceSplits(id!, "5"),
    enabled: !!id && !!race?.result_time,
  });

  // Same visibility rules as RaceDetailScreen.
  const showSplits = !!race?.result_time && race.sport === "run";
  const showWeather = !!race?.course && daysUntil(race.date) >= 0 && daysUntil(race.date) <= 10;

  return (
    <PublicPage noun="race" isLoading={isLoading} error={error}>
      {race && (
        <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
          <div>
            <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: "0 0 8px" }}>{race.name}</h1>
            <div style={{ fontSize: 13, color: "var(--ink3)", display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
              <span>
                {race.athlete_name} · {race.date}
                {race.sport && ` · ${race.sport.charAt(0).toUpperCase() + race.sport.slice(1)}`}
                {race.distance_km != null && ` · ${race.distance_km} km`}
              </span>
              {race.url && (
                <a href={race.url} target="_blank" rel="noopener noreferrer" style={{ color: "var(--ember)" }}>
                  Website ↗
                </a>
              )}
              {race.results_url && (
                <a href={race.results_url} target="_blank" rel="noopener noreferrer" style={{ color: "var(--ember)" }}>
                  Results ↗
                </a>
              )}
            </div>
          </div>

          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(290px, 1fr))", gap: 16 }}>
            <GoalResultCard
              race={race}
              pbS={null}
              predictionS={null}
              halves={splits?.halves ?? null}
              activity={race.activity}
              readOnly
            />
            {showWeather && <WeatherCard raceId={race.id} publicView />}
          </div>

          {showSplits && <SplitsCard raceId={race.id} distanceKm={race.distance_km} course={race.course} publicView />}

          {race.course && <PublicCourseCard course={race.course} />}
        </div>
      )}
    </PublicPage>
  );
}
