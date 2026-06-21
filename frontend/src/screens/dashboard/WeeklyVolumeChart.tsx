import { useMemo } from "react";
import type { Activity, Sport } from "../../api/types";
import { sportColor, sportLabel } from "../../lib/sportColors";

const SPORTS: Sport[] = ["bike", "run", "swim", "walk"];
const WEEKS = 8;

function weekStart(date: Date): string {
  const d = new Date(date);
  const day = d.getDay();
  const diff = (day + 6) % 7; // Monday-start week
  d.setDate(d.getDate() - diff);
  d.setHours(0, 0, 0, 0);
  return d.toISOString().slice(0, 10);
}

export function WeeklyVolumeChart({ activities }: { activities: Activity[] }) {
  const weeks = useMemo(() => {
    const buckets = new Map<string, Record<Sport, number>>();
    const today = new Date();
    for (let i = WEEKS - 1; i >= 0; i--) {
      const d = new Date(today);
      d.setDate(d.getDate() - i * 7);
      const key = weekStart(d);
      buckets.set(key, { bike: 0, run: 0, swim: 0, walk: 0 });
    }
    for (const activity of activities) {
      const key = weekStart(new Date(activity.start_date));
      const bucket = buckets.get(key);
      if (bucket) {
        bucket[activity.sport] += activity.moving_time / 3600;
      }
    }
    return Array.from(buckets.entries());
  }, [activities]);

  const maxHours = Math.max(1, ...weeks.map(([, hours]) => SPORTS.reduce((sum, s) => sum + hours[s], 0)));

  return (
    <div>
      <div style={{ fontSize: 12, color: "var(--ink3)", marginBottom: 14 }}>Hours by sport · last 8 weeks</div>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 8, height: 140 }}>
        {weeks.map(([weekKey, hours]) => {
          const total = SPORTS.reduce((sum, s) => sum + hours[s], 0);
          return (
            <div key={weekKey} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center" }}>
              <div className="mono" style={{ fontSize: 10, color: "var(--ink3)", marginBottom: 4 }}>
                {total > 0 ? `${total.toFixed(1)}h` : ""}
              </div>
              <div
                style={{
                  width: "100%",
                  height: 100,
                  display: "flex",
                  flexDirection: "column-reverse",
                  borderRadius: 4,
                  overflow: "hidden",
                }}
              >
                {SPORTS.map((sport) =>
                  hours[sport] > 0 ? (
                    <div
                      key={sport}
                      style={{ width: "100%", height: `${(hours[sport] / maxHours) * 100}px`, background: sportColor(sport) }}
                    />
                  ) : null,
                )}
              </div>
              <div style={{ fontSize: 10, color: "var(--ink3)", marginTop: 4 }}>
                {new Date(weekKey).toLocaleDateString(undefined, { month: "short", day: "numeric" })}
              </div>
            </div>
          );
        })}
      </div>
      <div style={{ display: "flex", gap: 14, marginTop: 12 }}>
        {SPORTS.map((sport) => (
          <div key={sport} style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12, color: "var(--ink2)" }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, background: sportColor(sport) }} />
            {sportLabel(sport)}
          </div>
        ))}
      </div>
    </div>
  );
}
