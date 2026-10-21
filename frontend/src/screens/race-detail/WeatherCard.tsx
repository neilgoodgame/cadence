import { useQuery } from "@tanstack/react-query";
import { getPublicRaceWeather } from "../../api/public";
import { getRaceWeather } from "../../api/races";

/** Race-detail grid card - race-day forecast from Open-Meteo. Only mounted by RaceDetailScreen
 * when the race has a course and is within the ~10-day forecast window; still handles
 * `available: false` gracefully (e.g. the course has no resolved start point, or the Open-Meteo
 * call failed) by rendering nothing rather than an error. */
/** `publicView` reads the unauthenticated public-race weather endpoint instead (see
 * PublicRaceScreen). */
export function WeatherCard({ raceId, publicView = false }: { raceId: string; publicView?: boolean }) {
  const { data } = useQuery({
    queryKey: [publicView ? "public-race-weather" : "race-weather", raceId],
    queryFn: () => (publicView ? getPublicRaceWeather : getRaceWeather)(raceId),
  });

  if (!data?.available) {
    return null;
  }

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 18, minWidth: 290 }}>
      <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", marginBottom: 10 }}>
        Forecast
      </div>
      <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
        <div className="mono" style={{ fontSize: 34, fontWeight: 800 }}>
          {data.temperature_max_c != null ? `${Math.round(data.temperature_max_c)}°` : "–"}
        </div>
        {data.temperature_min_c != null && (
          <div style={{ fontSize: 13, color: "var(--ink3)" }}>/ {Math.round(data.temperature_min_c)}° low</div>
        )}
      </div>
      <div style={{ fontSize: 12.5, color: "var(--ink3)", marginTop: 6 }}>
        {[
          data.precipitation_probability_pct != null && `${Math.round(data.precipitation_probability_pct)}% rain`,
          data.wind_speed_max_kmh != null && `${Math.round(data.wind_speed_max_kmh)} km/h wind`,
        ].filter(Boolean).join(" · ")}
      </div>
    </div>
  );
}
