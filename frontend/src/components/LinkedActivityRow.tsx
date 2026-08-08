import { Link } from "react-router-dom";

export interface LinkedActivityRowData {
  id: string;
  date: string;
  name: string;
  metric: string;
  tss: number;
}

/** One row in an "other activities matched to this workout" list - reused by the Activities
 * list (ActivityCard) and the Activity Analysis screen (MatchedWorkoutCard). */
export function LinkedActivityRow({ activity }: { activity: LinkedActivityRowData }) {
  return (
    <Link
      to={`/activities/${activity.id}`}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 14,
        padding: "9px 12px",
        borderRadius: 9,
        background: "var(--elev)",
        border: "1px solid var(--line)",
        textDecoration: "none",
      }}
    >
      <span className="mono" style={{ fontSize: 12, fontWeight: 600, color: "var(--ink)", width: 78, flexShrink: 0 }}>
        {activity.date}
      </span>
      <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)", flex: 1, minWidth: 0 }}>{activity.name}</span>
      <span className="mono" style={{ fontSize: 11.5, color: "var(--ink3)" }}>
        {activity.metric}
      </span>
      <span className="mono" style={{ fontSize: 11, fontWeight: 600, color: "var(--ink2)" }}>
        TSS {activity.tss}
      </span>
    </Link>
  );
}

export function LinkedActivitiesList({ activities }: { activities: LinkedActivityRowData[] }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      {activities.map((a) => (
        <LinkedActivityRow key={a.id} activity={a} />
      ))}
    </div>
  );
}

export function LinkedCountBadge({ count }: { count: number }) {
  return (
    <span
      className="mono"
      style={{
        fontSize: 10.5,
        fontWeight: 600,
        padding: "2px 8px",
        borderRadius: 20,
        background: "rgba(61,127,214,0.1)",
        color: "#3d7fd6",
      }}
    >
      {count} linked
    </span>
  );
}
