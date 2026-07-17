import { useNavigate } from "react-router-dom";
import { useQueries } from "@tanstack/react-query";
import { getActivity } from "../../api/activities";
import type { Activity } from "../../api/types";
import { Card } from "../../components/Card";
import { formatDuration } from "../../lib/format";
import { sportColor, sportLabel } from "../../lib/sportColors";

/** The per-leg breakdown shown on a multisport parent's analysis screen. */
export function MultisportLegs({ activity }: { activity: Activity }) {
  const navigate = useNavigate();
  const legs = useQueries({
    queries: activity.child_activity_ids.map((id) => ({
      queryKey: ["activity", id],
      queryFn: () => getActivity(id),
    })),
  });

  return (
    <Card>
      <div style={{ fontSize: 12, color: "var(--ink3)", marginBottom: 12 }}>
        Legs · {activity.child_activity_ids.length}
      </div>
      <div style={{ display: "flex", flexDirection: "column" }}>
        {legs.map((leg, i) => {
          const child = leg.data;
          if (!child) {
            return (
              <div key={activity.child_activity_ids[i]} style={{ padding: "10px 0", fontSize: 13, color: "var(--ink3)" }}>
                Loading…
              </div>
            );
          }
          return (
            <div
              key={child.id}
              onClick={() => navigate(`/activities/${child.id}`)}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 12,
                padding: "10px 0",
                borderTop: i > 0 ? "1px solid var(--line)" : "none",
                cursor: "pointer",
              }}
            >
              <span className="mono" style={{ fontSize: 12, color: "var(--ink3)", width: 14 }}>
                {i + 1}
              </span>
              <span
                style={{
                  fontSize: 11,
                  fontWeight: 600,
                  padding: "2px 8px",
                  borderRadius: 20,
                  background: sportColor(child.sport),
                  color: "#fff",
                  width: 70,
                  textAlign: "center",
                }}
              >
                {sportLabel(child.sport)}
              </span>
              <span style={{ flex: 1, fontSize: 13, color: "var(--ink2)" }}>
                {formatDuration(child.moving_time)}
                {child.distance_km > 0 ? ` · ${child.distance_km.toFixed(1)} km` : ""}
              </span>
              <span className="mono" style={{ fontSize: 13, color: "var(--ink2)" }}>
                {child.avg_hr != null ? `${child.avg_hr} bpm` : "—"}
              </span>
              <span className="mono" style={{ fontSize: 13, color: "var(--ember)", width: 64, textAlign: "right" }}>
                TSS {child.tss}
              </span>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
