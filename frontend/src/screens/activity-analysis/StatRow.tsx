import type { Activity } from "../../api/types";
import { formatDuration } from "../../lib/format";

function Stat({ label, value, unit }: { label: string; value: string | number | null; unit?: string }) {
  return (
    <div style={{ flex: 1 }}>
      <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 4 }}>
        {label.toUpperCase()}
      </div>
      <div className="mono" style={{ fontSize: 22, fontWeight: 600 }}>
        {value ?? "—"}
        {unit && value != null && <span style={{ fontSize: 13, fontWeight: 500, color: "var(--ink2)", marginLeft: 4 }}>{unit}</span>}
      </div>
    </div>
  );
}

export function StatRow({ activity }: { activity: Activity }) {
  return (
    <div style={{ display: "flex", padding: "18px 0", borderTop: "1px solid var(--line)", borderBottom: "1px solid var(--line)" }}>
      <Stat label="Distance" value={activity.distance_km.toFixed(2)} unit="km" />
      <Stat label="Moving time" value={formatDuration(activity.moving_time)} />
      <Stat label="Avg power" value={activity.avg_power} unit="w" />
      <Stat label="Norm. power" value={activity.norm_power} unit="w" />
      <Stat label="Intensity" value={activity.intensity.toFixed(2)} />
      <Stat label="TSS" value={activity.tss} />
      <Stat label="Avg HR" value={activity.avg_hr} unit="bpm" />
      <Stat label="Ascent" value={activity.ascent} unit="m" />
    </div>
  );
}
