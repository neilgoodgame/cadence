import { useQuery } from "@tanstack/react-query";
import { getLaps } from "../../api/activities";
import { formatDuration } from "../../lib/format";

export function LapsTab({ activityId }: { activityId: string }) {
  const { data } = useQuery({ queryKey: ["activity-laps", activityId], queryFn: () => getLaps(activityId) });
  const laps = data?.data ?? [];
  const maxPower = Math.max(1, ...laps.map((l) => l.avg_power ?? 0));

  if (laps.length === 0) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>No laps recorded.</div>;
  }

  return (
    <table style={{ width: "100%", fontSize: 13, borderCollapse: "collapse" }}>
      <thead>
        <tr style={{ textAlign: "left", color: "var(--ink3)", fontSize: 11 }}>
          <th style={{ paddingBottom: 8 }}>Lap</th>
          <th style={{ paddingBottom: 8 }}>Distance</th>
          <th style={{ paddingBottom: 8 }}>Time</th>
          <th style={{ paddingBottom: 8 }}>Speed</th>
          <th style={{ paddingBottom: 8 }}>Avg Power</th>
          <th style={{ paddingBottom: 8 }}>Avg HR</th>
        </tr>
      </thead>
      <tbody>
        {laps.map((lap) => (
          <tr key={lap.index} style={{ borderTop: "1px solid var(--line)" }}>
            <td style={{ padding: "8px 0" }}>
              <span style={{ display: "inline-block", width: 24, height: 24, borderRadius: "50%", background: "var(--elev)", textAlign: "center", lineHeight: "24px", fontSize: 11 }}>
                {lap.index}
              </span>
            </td>
            <td className="mono">{lap.distance_km.toFixed(2)} km</td>
            <td className="mono">{formatDuration(lap.duration)}</td>
            <td className="mono">{((lap.distance_km / (lap.duration / 3600)) || 0).toFixed(1)} km/h</td>
            <td>
              {lap.avg_power != null ? (
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <div style={{ width: 60, height: 6, background: "var(--elev)", borderRadius: 3 }}>
                    <div style={{ width: `${(lap.avg_power / maxPower) * 100}%`, height: "100%", background: "var(--ember)", borderRadius: 3 }} />
                  </div>
                  <span className="mono">{lap.avg_power} w</span>
                </div>
              ) : (
                <span className="mono" style={{ color: "var(--ink3)" }}>—</span>
              )}
            </td>
            <td className="mono">{lap.avg_hr != null ? `${lap.avg_hr} bpm` : "—"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
