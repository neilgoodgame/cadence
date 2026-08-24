import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { getCalendar } from "../../api/scheduling";
import { listWorkouts } from "../../api/workouts";
import { Card } from "../../components/Card";
import { dateKey } from "../../lib/calendar";
import { formatDuration } from "../../lib/format";
import { sportColor } from "../../lib/sportColors";

const DOW_LABELS = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];
// Wide enough that a lightly-scheduled athlete still sees their next few sessions, not just
// whatever happens to fall in the next few days.
const LOOKAHEAD_DAYS = 60;
const MAX_ROWS = 3;

export function UpcomingWorkoutsCard() {
  const navigate = useNavigate();

  // Stable across re-renders within the same day, same reasoning as DashboardScreen's own
  // historyWindow() - a fresh `new Date()` on every render would still produce the same date
  // string until the day actually rolls over, but memoizing keeps that explicit.
  const { from, to } = useMemo(() => {
    const start = new Date();
    const end = new Date();
    end.setDate(end.getDate() + LOOKAHEAD_DAYS);
    return { from: dateKey(start), to: dateKey(end) };
  }, []);

  const calendarQuery = useQuery({ queryKey: ["calendar", from, to], queryFn: () => getCalendar(from, to) });
  // Unfiltered - same shape CalendarScreen fetches, so the two screens share this cache entry.
  const workoutsQuery = useQuery({ queryKey: ["workouts"], queryFn: () => listWorkouts() });

  const workoutById = useMemo(() => {
    return new Map(workoutsQuery.data?.data.map((w) => [w.id, w]));
  }, [workoutsQuery.data]);

  const upcoming = useMemo(() => {
    return (calendarQuery.data?.data ?? [])
      .filter((entry) => entry.status === "planned" && workoutById.has(entry.workout_id))
      .sort((a, b) => a.date.localeCompare(b.date))
      .slice(0, MAX_ROWS)
      .map((entry) => {
        const workout = workoutById.get(entry.workout_id)!;
        const d = new Date(entry.date + "T00:00:00");
        return {
          id: entry.id,
          dow: DOW_LABELS[d.getDay()],
          dateNum: d.getDate(),
          name: workout.name,
          meta: formatDuration(workout.duration),
          tss: workout.tss,
          sport: workout.sport,
        };
      });
  }, [calendarQuery.data, workoutById]);

  return (
    <Card>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14 }}>
        <div style={{ fontSize: 16, fontWeight: 700, letterSpacing: "-0.01em", color: "var(--ink)" }}>Upcoming workouts</div>
        <div onClick={() => navigate("/workouts")} style={{ fontSize: 13, fontWeight: 600, color: "var(--ember)", cursor: "pointer" }}>
          Library →
        </div>
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
        {upcoming.length === 0 ? (
          <div style={{ fontSize: 13, color: "var(--ink3)", padding: "8px 6px" }}>
            No workouts scheduled — plan one in the Workout Designer.
          </div>
        ) : (
          upcoming.map((w) => (
            <div
              key={w.id}
              onClick={() => navigate(`/scheduled/${w.id}`)}
              style={{ display: "flex", alignItems: "center", gap: 14, padding: "10px 6px", borderRadius: 10, cursor: "pointer" }}
            >
              <div style={{ width: 40, flexShrink: 0, textAlign: "center" }}>
                <div className="mono" style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.06em", color: "var(--ink3)" }}>{w.dow}</div>
                <div style={{ fontSize: 17, fontWeight: 800, color: "var(--ink)", lineHeight: 1.15 }}>{w.dateNum}</div>
              </div>
              <div style={{ width: 4, height: 32, borderRadius: 3, flexShrink: 0, background: sportColor(w.sport) }} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 14, fontWeight: 700, color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                  {w.name}
                </div>
                <div className="mono" style={{ fontSize: 12, color: "var(--ink3)", marginTop: 1 }}>{w.meta}</div>
              </div>
              <div className="mono" style={{ fontSize: 11.5, fontWeight: 700, color: "var(--ink2)", flexShrink: 0 }}>{w.tss} TSS</div>
            </div>
          ))
        )}
      </div>
    </Card>
  );
}
