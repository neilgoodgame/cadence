import { Link } from "react-router-dom";
import type { Race, Sport } from "../../api/types";
import { SportIcon } from "../../lib/sportIcons";

function daysUntil(dateStr: string): number {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((new Date(dateStr + "T00:00:00").getTime() - today.getTime()) / 86_400_000);
}

function weekOf(race: Race): number | null {
  if (race.block_start == null || race.block_weeks == null) return null;
  const start = new Date(race.block_start + "T00:00:00");
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const diff = Math.floor((today.getTime() - start.getTime()) / 86_400_000 / 7) + 1;
  return Math.min(Math.max(diff, 1), race.block_weeks);
}

function priorityBadgeStyle(priority: Race["priority"]): React.CSSProperties {
  const color = priority === "A" ? "var(--ember)" : priority === "B" ? "var(--ink2)" : "var(--ink3)";
  return {
    fontFamily: "'JetBrains Mono',monospace",
    fontSize: 9.5,
    fontWeight: 700,
    padding: "1px 6px",
    borderRadius: 5,
    border: `1px ${priority === "C" ? "dashed" : "solid"} ${color}`,
    color,
    flexShrink: 0,
  };
}

function RaceRow({ race, nested = false }: { race: Race; nested?: boolean }) {
  const days = daysUntil(race.date);
  const week = weekOf(race);

  return (
    <Link
      to={`/races/${race.id}`}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        padding: "10px 0",
        paddingLeft: nested ? 28 : 0,
        borderTop: "1px solid var(--line)",
        textDecoration: "none",
        color: "inherit",
      }}
    >
      {nested && <span style={{ color: "var(--ink3)", fontSize: 13 }}>&#8627;</span>}
      <div className="mono" style={{ fontSize: 12, color: "var(--ink3)", width: 76, flexShrink: 0 }}>
        {race.date}
      </div>
      <span style={priorityBadgeStyle(race.priority)}>{race.priority}</span>
      {race.sport && <SportIcon sport={race.sport as Sport} size={14} />}
      <div style={{ flex: 1, minWidth: 0, fontWeight: 600, fontSize: 14 }}>{race.name}</div>
      <div style={{ fontSize: 12, color: "var(--ink3)", flexShrink: 0 }}>
        {days >= 0 ? `${days} days` : "today"}
        {week != null && race.block_weeks != null && ` · block week ${week} of ${race.block_weeks}`}
      </div>
    </Link>
  );
}

/** A races and parentless B/C races as rows; B/C children with a parent nest underneath it
 * (`↳`), per README §4.1. */
export function UpcomingList({ races }: { races: Race[] }) {
  const topLevel = races.filter((r) => r.priority === "A" || r.parent_race_id == null);
  const childrenByParent = new Map<string, Race[]>();
  for (const r of races) {
    if (r.parent_race_id) {
      childrenByParent.set(r.parent_race_id, [...(childrenByParent.get(r.parent_race_id) ?? []), r]);
    }
  }

  if (topLevel.length === 0) {
    return <div style={{ fontSize: 13, color: "var(--ink3)" }}>No upcoming races.</div>;
  }

  return (
    <div>
      {topLevel.map((race) => (
        <div key={race.id}>
          <RaceRow race={race} />
          {(childrenByParent.get(race.id) ?? []).map((child) => <RaceRow key={child.id} race={child} nested />)}
        </div>
      ))}
    </div>
  );
}
