import { useNavigate } from "react-router-dom";
import type { Activity } from "../../api/types";
import { formatDate, formatDuration } from "../../lib/format";
import { sportColor, sportLabel } from "../../lib/sportColors";

export function RecentActivitiesTable({ activities }: { activities: Activity[] }) {
  const navigate = useNavigate();

  if (activities.length === 0) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>No activities yet - upload one to get started.</div>;
  }

  return (
    <table style={{ width: "100%", fontSize: 13, borderCollapse: "collapse" }}>
      <thead>
        <tr style={{ textAlign: "left", color: "var(--ink3)", fontSize: 11 }}>
          <th style={{ paddingBottom: 8 }}>Activity</th>
          <th style={{ paddingBottom: 8 }}>Distance</th>
          <th style={{ paddingBottom: 8 }}>Time</th>
          <th style={{ paddingBottom: 8 }}>Load</th>
          <th style={{ paddingBottom: 8 }}>Intensity</th>
          <th style={{ paddingBottom: 8 }}>Avg</th>
        </tr>
      </thead>
      <tbody>
        {activities.slice(0, 6).map((activity) => (
          <tr
            key={activity.id}
            onClick={() => navigate(`/activities/${activity.id}`)}
            style={{ cursor: "pointer", borderTop: "1px solid var(--line)" }}
          >
            <td style={{ padding: "10px 0" }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span style={{ width: 8, height: 8, borderRadius: 2, background: sportColor(activity.sport) }} />
                <div>
                  <div style={{ fontWeight: 600 }}>{activity.name}</div>
                  <div style={{ fontSize: 11, color: "var(--ink3)" }}>
                    {formatDate(activity.start_date)} · {sportLabel(activity.sport)}
                  </div>
                </div>
              </div>
            </td>
            <td className="mono">{activity.distance_km.toFixed(1)} km</td>
            <td className="mono">{formatDuration(activity.moving_time)}</td>
            <td className="mono" style={{ color: "var(--ember)" }}>
              {activity.tss}
            </td>
            <td className="mono">{activity.intensity ? activity.intensity.toFixed(2) : "-"}</td>
            <td className="mono">{activity.avg_power ? `${activity.avg_power} w` : `${activity.avg_hr} bpm`}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
