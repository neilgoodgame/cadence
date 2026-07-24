import { useQuery, useQueries } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import type { Activity } from "../../api/types";
import { getStreams } from "../../api/activities";
import { listZones } from "../../api/athletes";
import { bucketIntoZones } from "../../lib/zones";
import { formatDuration, formatPace } from "../../lib/format";
import { sportColor } from "../../lib/sportColors";

const DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const ZONE_COLORS = ["var(--zone-1)", "var(--zone-2)", "var(--zone-3)", "var(--zone-4)", "var(--zone-5)"];
const RESOLUTION_SECONDS = 5;

function localIso(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function WeekHrDistribution({ activities, athleteId }: { activities: Activity[]; athleteId: string }) {
  const hrActivities = activities.filter((a) => a.avg_hr != null);

  const zonesQuery = useQuery({
    queryKey: ["zones", athleteId],
    queryFn: () => listZones(athleteId),
  });

  const streamQueries = useQueries({
    queries: hrActivities.map((a) => ({
      queryKey: ["activity-streams-zones", a.id, "heartrate"],
      queryFn: () => getStreams(a.id, ["heartrate"], "medium"),
    })),
  });

  if (hrActivities.length === 0) return null;

  const zoneSet = zonesQuery.data?.data.find((z) => z.type === "heart_rate");
  if (!zoneSet) return null;

  const allLoaded = streamQueries.every((q) => q.data != null);
  if (!allLoaded) {
    return (
      <div style={{ marginTop: 20, paddingTop: 16, borderTop: "1px solid var(--line)" }}>
        <div className="mono" style={{ fontSize: 11, color: "#e0442e", fontWeight: 600, letterSpacing: "0.06em", marginBottom: 10 }}>
          HEART RATE ZONES
        </div>
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>Loading…</div>
      </div>
    );
  }

  const allSamples = streamQueries.flatMap((q) => q.data!.fields["heartrate"] ?? []);
  const zoneTimes = bucketIntoZones(allSamples, zoneSet, RESOLUTION_SECONDS);
  const maxSeconds = Math.max(1, ...zoneTimes.map((z) => z.seconds));

  return (
    <div style={{ marginTop: 20, paddingTop: 16, borderTop: "1px solid var(--line)" }}>
      <div className="mono" style={{ fontSize: 11, color: "#e0442e", fontWeight: 600, letterSpacing: "0.06em", marginBottom: 10 }}>
        HEART RATE ZONES
      </div>
      {zoneTimes.map((zone, i) => (
        <div key={zone.name} style={{ display: "flex", alignItems: "center", gap: 10, padding: "5px 0", fontSize: 13 }}>
          <span style={{ width: 8, height: 8, borderRadius: 2, flexShrink: 0, background: ZONE_COLORS[i % ZONE_COLORS.length] }} />
          <span style={{ width: 110, flexShrink: 0 }}>{zone.name}</span>
          <div style={{ flex: 1, height: 6, background: "var(--elev)", borderRadius: 3 }}>
            <div
              style={{
                width: `${(zone.seconds / maxSeconds) * 100}%`,
                height: "100%",
                background: ZONE_COLORS[i % ZONE_COLORS.length],
                borderRadius: 3,
              }}
            />
          </div>
          <span className="mono" style={{ width: 60, textAlign: "right", flexShrink: 0, color: "var(--ink2)" }}>
            {formatDuration(zone.seconds)}
          </span>
        </div>
      ))}
    </div>
  );
}

export function WeekCalendar({ activities, athleteId }: { activities: Activity[]; athleteId: string }) {
  const navigate = useNavigate();

  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const todayIso = localIso(today);

  const days = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(today);
    d.setDate(d.getDate() - 6 + i);
    return d;
  });

  const cutoff = localIso(days[0]);

  const byDate = new Map<string, Activity[]>();
  for (const a of activities) {
    const date = localIso(new Date(a.start_date));
    if (date >= cutoff) {
      if (!byDate.has(date)) byDate.set(date, []);
      byDate.get(date)!.push(a);
    }
  }

  const weekActivities = [...byDate.values()].flat();
  const trainingActivities = weekActivities.filter((a) => a.sport !== "walk");
  const totalTimeS = trainingActivities.reduce((s, a) => s + a.moving_time, 0);
  const weekTss = trainingActivities.reduce((s, a) => s + a.tss, 0);

  const runs = trainingActivities.filter((a) => a.sport === "run");
  const runDistanceKm = runs.reduce((s, a) => s + a.distance_km, 0);
  const runTimeS = runs.reduce((s, a) => s + a.moving_time, 0);
  const avgPaceSecPerKm = runDistanceKm > 0 ? runTimeS / runDistanceKm : null;

  const rides = trainingActivities.filter((a) => a.sport === "bike");
  const bikeDistanceKm = rides.reduce((s, a) => s + a.distance_km, 0);
  const poweredRides = rides.filter((a) => a.avg_power != null);
  const avgBikePower =
    poweredRides.length > 0
      ? Math.round(poweredRides.reduce((s, a) => s + a.avg_power!, 0) / poweredRides.length)
      : null;

  return (
    <div>
      <h2 style={{ fontSize: 16, fontWeight: 700, margin: "0 0 14px" }}>This week</h2>

      <div
        style={{
          display: "flex",
          gap: 32,
          marginBottom: 18,
          paddingBottom: 14,
          borderBottom: "1px solid var(--line)",
        }}
      >
        {[
          { label: "Training time", value: formatDuration(totalTimeS) },
          { label: "TSS", value: Math.round(weekTss).toString() },
          null,
          { label: "Run distance", value: `${runDistanceKm.toFixed(1)} km` },
          { label: "Runs", value: String(runs.length) },
          { label: "Avg run pace", value: avgPaceSecPerKm != null ? formatPace(avgPaceSecPerKm) : "—" },
          ...(rides.length > 0
            ? [
                null,
                { label: "Bike distance", value: `${bikeDistanceKm.toFixed(1)} km` },
                { label: "Rides", value: String(rides.length) },
                { label: "Avg power", value: avgBikePower != null ? `${avgBikePower} W` : "—" },
              ]
            : []),
        ].map((stat, i) =>
          stat === null ? (
            <div key={i} style={{ width: 1, background: "var(--line)", alignSelf: "stretch" }} />
          ) : (
            <div key={stat.label}>
              <div style={{ fontSize: 11, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.06em", fontWeight: 700, marginBottom: 2 }}>
                {stat.label}
              </div>
              <div className="mono" style={{ fontSize: 18, fontWeight: 800, color: "var(--ink)" }}>
                {stat.value}
              </div>
            </div>
          )
        )}
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(7, 1fr)", gap: 10 }}>
        {days.map((day) => {
          const iso = localIso(day);
          const isToday = iso === todayIso;
          const dayActivities = byDate.get(iso) ?? [];

          return (
            <div key={iso}>
              <div
                style={{
                  paddingBottom: 8,
                  marginBottom: 8,
                  borderBottom: `2px solid ${isToday ? "var(--ember)" : "var(--line)"}`,
                }}
              >
                <div
                  className="mono"
                  style={{
                    fontSize: 10,
                    fontWeight: 700,
                    textTransform: "uppercase",
                    letterSpacing: "0.08em",
                    color: isToday ? "var(--ember)" : "var(--ink3)",
                  }}
                >
                  {DAY_NAMES[day.getDay()]}
                </div>
                <div
                  style={{
                    fontSize: 20,
                    fontWeight: 800,
                    lineHeight: 1.2,
                    color: isToday ? "var(--ink)" : "var(--ink2)",
                  }}
                >
                  {day.getDate()}
                </div>
              </div>

              <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                {dayActivities.length === 0 ? (
                  <div style={{ fontSize: 12, color: "var(--ink3)", textAlign: "center", paddingTop: 4 }}>—</div>
                ) : (
                  dayActivities.map((a) => (
                    <div
                      key={a.id}
                      onClick={() => navigate(`/activities/${a.id}`)}
                      style={{
                        padding: "6px 8px",
                        borderRadius: 6,
                        background: "var(--elev)",
                        cursor: "pointer",
                        borderLeft: `3px solid ${sportColor(a.sport)}`,
                      }}
                    >
                      <div
                        style={{
                          fontSize: 12,
                          fontWeight: 600,
                          overflow: "hidden",
                          whiteSpace: "nowrap",
                          textOverflow: "ellipsis",
                          color: "var(--ink)",
                          marginBottom: 2,
                        }}
                      >
                        {a.name}
                      </div>
                      <div className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>
                        {a.distance_km > 0 ? `${a.distance_km.toFixed(1)} km · ` : ""}
                        {formatDuration(a.moving_time)}
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          );
        })}
      </div>

      <WeekHrDistribution activities={trainingActivities} athleteId={athleteId} />
    </div>
  );
}
