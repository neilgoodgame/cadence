import { useNavigate } from "react-router-dom";
import type { Activity } from "../../api/types";
import { formatDuration } from "../../lib/format";
import { sportColor } from "../../lib/sportColors";

const DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

export function WeekCalendar({ activities }: { activities: Activity[] }) {
  const navigate = useNavigate();

  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const todayIso = today.toISOString().slice(0, 10);

  const days = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(today);
    d.setDate(d.getDate() - 6 + i);
    return d;
  });

  const cutoff = days[0].toISOString().slice(0, 10);

  const byDate = new Map<string, Activity[]>();
  for (const a of activities) {
    const date = a.start_date.slice(0, 10);
    if (date >= cutoff) {
      if (!byDate.has(date)) byDate.set(date, []);
      byDate.get(date)!.push(a);
    }
  }

  return (
    <div>
      <h2 style={{ fontSize: 16, fontWeight: 700, margin: "0 0 14px" }}>This week</h2>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(7, 1fr)", gap: 10 }}>
        {days.map((day) => {
          const iso = day.toISOString().slice(0, 10);
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
    </div>
  );
}
