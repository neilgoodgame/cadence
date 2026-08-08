import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Card } from "../../components/Card";
import { LinkedActivitiesList, LinkedCountBadge } from "../../components/LinkedActivityRow";
import type { Activity, ZoneSet } from "../../api/types";
import { formatDate, formatDuration, formatKeyMetric } from "../../lib/format";
import { sportColor, sportLabel } from "../../lib/sportColors";
import { tagColor, tagRgba } from "../../lib/tagColors";
import { ZoneBar } from "./ZoneBar";

export function ActivityCard({
  activity,
  hrZones,
  linked = [],
}: {
  activity: Activity;
  hrZones: ZoneSet | undefined;
  linked?: Activity[];
}) {
  const [expanded, setExpanded] = useState(false);
  const navigate = useNavigate();
  const linkedRows = linked.map((a) => ({ id: a.id, date: formatDate(a.start_date), name: a.name, metric: formatKeyMetric(a), tss: a.tss }));

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
                  style={{
                    display: "inline-flex",
                    alignItems: "center",
                    gap: 6,
                    fontSize: 11,
                    fontWeight: 600,
                    color: "var(--ink2)",
                    background: tagRgba(tag, 0.1),
                    border: `1px solid ${tagRgba(tag, 0.3)}`,
                    padding: "2px 8px 2px 6px",
                    borderRadius: 20,
                  }}
                >
                  <span style={{ width: 6, height: 6, borderRadius: "50%", background: tagColor(tag), flexShrink: 0 }} />
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
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            {linkedRows.length > 0 && <LinkedCountBadge count={linkedRows.length} />}
            <button
              onClick={() => setExpanded(!expanded)}
              style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12, cursor: "pointer", padding: 0 }}
            >
              {expanded ? "Hide zones" : "Show zones"}
            </button>
          </div>
        </div>
      </div>

      {expanded && (
        <div style={{ marginTop: 14, paddingTop: 14, borderTop: "1px solid var(--line)" }}>
          <ZoneBar activityId={activity.id} hrZones={hrZones} />
          {linkedRows.length > 0 && (
            <div style={{ marginTop: 16, paddingTop: 16, borderTop: "1px solid var(--line)" }}>
              <div
                className="mono"
                style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.06em", color: "var(--ink3)", marginBottom: 10 }}
              >
                OTHER ACTIVITIES MATCHED TO THIS WORKOUT
              </div>
              <LinkedActivitiesList activities={linkedRows} />
            </div>
          )}
        </div>
      )}
    </Card>
  );
}
