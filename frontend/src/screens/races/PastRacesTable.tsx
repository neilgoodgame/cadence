import { Link } from "react-router-dom";
import type { Race, Sport } from "../../api/types";
import { formatDuration, formatPace, parseDuration } from "../../lib/format";
import { SportIcon } from "../../lib/sportIcons";

function yearOf(race: Race): number {
  return new Date(race.date + "T00:00:00").getFullYear();
}

function bestResultByDistance(races: Race[]): Map<number, Race> {
  const best = new Map<number, Race>();
  for (const race of races) {
    if (race.distance_km == null || race.result_time == null) continue;
    const key = Math.round(race.distance_km * 10) / 10;
    const resultS = parseDuration(race.result_time);
    const currentBestS = best.has(key) ? parseDuration(best.get(key)!.result_time!) : null;
    if (resultS != null && (currentBestS == null || resultS < currentBestS)) best.set(key, race);
  }
  return best;
}

/** Past races grouped by year, with a PB badge per distance - README §4.1. */
export function PastRacesTable({ races }: { races: Race[] }) {
  if (races.length === 0) {
    return <div style={{ fontSize: 13, color: "var(--ink3)" }}>No past races yet.</div>;
  }

  const pbByDistance = bestResultByDistance(races);
  const byYear = new Map<number, Race[]>();
  for (const race of races) {
    const year = yearOf(race);
    byYear.set(year, [...(byYear.get(year) ?? []), race]);
  }
  const years = [...byYear.keys()].sort((a, b) => b - a);
  for (const yearRaces of byYear.values()) yearRaces.sort((a, b) => b.date.localeCompare(a.date));

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      {years.map((year) => (
        <div key={year}>
          <h3
            style={{ fontSize: 12, fontWeight: 700, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.08em", margin: "0 0 6px" }}
          >
            {year}
          </h3>
          <div
            style={{ display: "grid", gridTemplateColumns: "90px 1fr 80px 90px 90px 80px 90px", fontSize: 10.5, fontWeight: 700, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.05em", padding: "0 0 6px", borderBottom: "1px solid var(--line)" }}
          >
            <div>Date</div>
            <div>Race</div>
            <div>Distance</div>
            <div>Goal</div>
            <div>Result</div>
            <div>vs goal</div>
            <div>Pace</div>
          </div>
          {byYear.get(year)!.map((race) => {
            const isPb = race.distance_km != null && pbByDistance.get(Math.round(race.distance_km * 10) / 10)?.id === race.id;
            const resultS = race.result_time ? parseDuration(race.result_time) : null;
            const goalS = race.goal_time ? parseDuration(race.goal_time) : null;
            const deltaS = resultS != null && goalS != null ? resultS - goalS : null;
            const pace = resultS != null && race.distance_km ? formatPace(resultS / race.distance_km) : null;

            return (
              <Link
                key={race.id}
                to={`/races/${race.id}`}
                style={{ display: "grid", gridTemplateColumns: "90px 1fr 80px 90px 90px 80px 90px", padding: "9px 0", borderBottom: "1px solid var(--line)", textDecoration: "none", color: "inherit", alignItems: "center" }}
              >
                <div className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>{race.date}</div>
                <div style={{ display: "flex", alignItems: "center", gap: 6, minWidth: 0 }}>
                  <span style={{ fontSize: 9.5, fontWeight: 700, color: race.priority === "A" ? "var(--ember)" : "var(--ink3)" }}>
                    {race.priority}
                  </span>
                  {race.sport && <SportIcon sport={race.sport as Sport} size={13} />}
                  <span style={{ fontWeight: 600, fontSize: 13.5, overflow: "hidden", textOverflow: "ellipsis" }}>{race.name}</span>
                  {isPb && (
                    <span style={{ fontSize: 9.5, fontWeight: 700, color: "#2fa66a", border: "1px solid #2fa66a", borderRadius: 4, padding: "0 4px" }}>
                      PB
                    </span>
                  )}
                </div>
                <div className="mono" style={{ fontSize: 12.5 }}>{race.distance_km != null ? `${race.distance_km} km` : "–"}</div>
                <div className="mono" style={{ fontSize: 12.5 }}>{race.goal_time ?? "–"}</div>
                <div className="mono" style={{ fontSize: 12.5 }}>{race.result_time ?? "–"}</div>
                <div className="mono" style={{ fontSize: 12.5, color: deltaS == null ? "var(--ink3)" : deltaS <= 0 ? "#2fa66a" : "#e0442e" }}>
                  {deltaS == null ? "–" : `${deltaS <= 0 ? "-" : "+"}${formatDuration(Math.abs(deltaS))}`}
                </div>
                <div className="mono" style={{ fontSize: 12.5 }}>{pace ?? "–"}</div>
              </Link>
            );
          })}
        </div>
      ))}
    </div>
  );
}
