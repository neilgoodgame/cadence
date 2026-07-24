import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { listRaces } from "../../api/races";

function daysUntil(dateStr: string): number {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const race = new Date(dateStr + "T00:00:00");
  return Math.round((race.getTime() - today.getTime()) / 86400000);
}

export function NextRaceCard() {
  const navigate = useNavigate();
  const { data } = useQuery({ queryKey: ["races"], queryFn: listRaces });

  const today = new Date().toISOString().slice(0, 10);
  const next = data?.data.find((r) => r.date >= today);

  if (!next) return null;

  const days = daysUntil(next.date);
  const weeksLabel =
    days === 0 ? "Today" :
    days === 1 ? "Tomorrow" :
    days < 14 ? `${days} days` :
    `${Math.round(days / 7)} weeks`;

  return (
    <div
      onClick={() => navigate("/preferences?tab=races")}
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        padding: "14px 20px",
        borderRadius: 12,
        border: "1px solid var(--ember)",
        cursor: "pointer",
        background: "var(--card)",
      }}
    >
      <div>
        <div style={{ fontSize: 11, fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.08em", color: "var(--ember)", marginBottom: 4 }}>
          Next race
        </div>
        <div style={{ fontSize: 18, fontWeight: 800, color: "var(--ink)" }}>{next.name}</div>
        <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>
          {next.date}
          {next.sport && ` · ${next.sport.charAt(0).toUpperCase() + next.sport.slice(1)}`}
          {next.distance_km != null && ` · ${next.distance_km} km`}
          {next.goal_time && ` · Goal: ${next.goal_time}`}
        </div>
      </div>
      <div style={{ textAlign: "right", flexShrink: 0, marginLeft: 20 }}>
        <div className="mono" style={{ fontSize: 36, fontWeight: 800, lineHeight: 1, color: "var(--ember)" }}>
          {days === 0 ? "🏁" : days}
        </div>
        {days > 0 && (
          <div style={{ fontSize: 11, color: "var(--ink3)", marginTop: 2 }}>{weeksLabel}</div>
        )}
      </div>
    </div>
  );
}
