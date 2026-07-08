import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Card } from "../../components/Card";
import type { Activity, ZoneSet } from "../../api/types";
import { formatDate, formatDuration } from "../../lib/format";
import { sportColor, sportLabel } from "../../lib/sportColors";
import { ZoneBar } from "./ZoneBar";

export function ActivityCard({ activity, hrZones }: { activity: Activity; hrZones: ZoneSet | undefined }) {
  const [expanded, setExpanded] = useState(false);
  const navigate = useNavigate();

  const summary = [
    `${formatDuration(activity.moving_time)}`,
    `${activity.distance_km.toFixed(1)} km`,
    activity.avg_power ? `${activity.avg_power} W` : null,
    activity.avg_air_temp != null ? `${activity.avg_air_temp.toFixed(0)}°C` : null,
    activity.avg_humidity != null ? `${activity.avg_humidity}% RH` : null,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <Card>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div style={{ flex: 1, cursor: "pointer" }} onClick={() => navigate(`/activities/${activity.id}`)}>
          <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 4 }}>
            <span
              style={{
                fontSize: 11,
                fontWeight: 600,
                padding: "2px 8px",
                borderRadius: 20,
                background: sportColor(activity.sport),
                color: "#fff",
              }}
            >
              {sportLabel(activity.sport)}
            </span>
            <span style={{ fontSize: 12, color: "var(--ink3)" }}>{formatDate(activity.start_date)}</span>
            {activity.workout_id && (
              <span style={{ fontSize: 11, color: "#2fa66a", fontWeight: 600 }}>✓ Matched</span>
            )}
            {activity.environment === "indoor" && (
              <span style={{ fontSize: 11, color: "var(--ink3)", background: "var(--elev)", padding: "1px 6px", borderRadius: 6 }}>
                Indoor
              </span>
            )}
          </div>
          <div style={{ fontWeight: 700, fontSize: 15, marginBottom: 4 }}>{activity.name}</div>
          <div style={{ fontSize: 13, color: "var(--ink2)" }}>{summary}</div>
          {activity.tags.length > 0 && (
            <div style={{ display: "flex", gap: 6, marginTop: 8 }}>
              {activity.tags.map((tag) => (
                <span
                  key={tag}
                  style={{ fontSize: 11, color: "var(--ink2)", background: "var(--elev)", padding: "2px 8px", borderRadius: 20 }}
                >
                  {tag}
                </span>
              ))}
            </div>
          )}
        </div>

        <div style={{ textAlign: "right", display: "flex", flexDirection: "column", gap: 4 }}>
          <div className="mono" style={{ fontSize: 13, color: "var(--ink2)" }}>
            {activity.avg_hr != null ? `${activity.avg_hr} bpm` : "—"}
          </div>
          <div className="mono" style={{ fontSize: 13, color: "var(--ember)" }}>
            TSS {activity.tss}
          </div>
          <button
            onClick={() => setExpanded(!expanded)}
            style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12, cursor: "pointer", padding: 0 }}
          >
            {expanded ? "Hide zones" : "Show zones"}
          </button>
        </div>
      </div>

      {expanded && (
        <div style={{ marginTop: 14, paddingTop: 14, borderTop: "1px solid var(--line)" }}>
          <ZoneBar activityId={activity.id} hrZones={hrZones} />
        </div>
      )}
    </Card>
  );
}
