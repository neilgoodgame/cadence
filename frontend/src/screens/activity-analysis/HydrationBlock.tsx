import type { Activity } from "../../api/types";

function weightLossColor(pct: number): string {
  if (pct >= 3) return "#e0442e";
  if (pct >= 2) return "#f0a02e";
  return "#2fa66a";
}

/** Only renders when both weigh-ins are present - never fabricates a number from a partial set. */
export function HydrationBlock({ activity }: { activity: Activity }) {
  if (activity.start_weight_kg == null || activity.end_weight_kg == null) {
    return null;
  }

  const fluidsL = (activity.fluids_ml ?? 0) / 1000;
  const weightLossKg = activity.start_weight_kg - activity.end_weight_kg;
  const weightLossPct = (weightLossKg / activity.start_weight_kg) * 100;
  const hours = activity.moving_time / 3600;
  const sweatRateMlPerHour = hours > 0 ? ((weightLossKg + fluidsL) / hours) * 1000 : 0;

  return (
    <div style={{ display: "flex", gap: 32, padding: "14px 0", borderTop: "1px solid var(--line)", fontSize: 13 }}>
      <div>
        <div style={{ color: "var(--ink3)", fontSize: 11, marginBottom: 4 }}>SWEAT RATE</div>
        <div className="mono" style={{ fontSize: 16, fontWeight: 600 }}>
          {Math.round(sweatRateMlPerHour)} ml/h
        </div>
      </div>
      <div>
        <div style={{ color: "var(--ink3)", fontSize: 11, marginBottom: 4 }}>BODY WEIGHT LOST</div>
        <div className="mono" style={{ fontSize: 16, fontWeight: 600, color: weightLossColor(weightLossPct) }}>
          {weightLossPct.toFixed(1)}%
        </div>
      </div>
      <div>
        <div style={{ color: "var(--ink3)", fontSize: 11, marginBottom: 4 }}>START → END</div>
        <div className="mono" style={{ fontSize: 16, fontWeight: 600 }}>
          {activity.start_weight_kg.toFixed(1)} → {activity.end_weight_kg.toFixed(1)} kg
        </div>
      </div>
      <div>
        <div style={{ color: "var(--ink3)", fontSize: 11, marginBottom: 4 }}>FLUIDS CONSUMED</div>
        <div className="mono" style={{ fontSize: 16, fontWeight: 600 }}>
          {activity.fluids_ml ?? 0} ml
        </div>
      </div>
    </div>
  );
}
