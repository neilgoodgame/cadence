import { useQuery } from "@tanstack/react-query";
import { getCurves } from "../../api/activities";
import type { Activity, Athlete } from "../../api/types";
import { formatDuration } from "../../lib/format";

function Stat({ label, value, unit }: { label: string; value: string | number | null; unit?: string }) {
  return (
    <div>
      <div className="mono" style={{ fontSize: 21, fontWeight: 600 }}>
        {value ?? "—"}
        {unit && value != null && <span style={{ fontSize: 12, fontWeight: 500, color: "var(--ink3)", marginLeft: 3 }}>{unit}</span>}
      </div>
      <div style={{ fontSize: 11, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.05em", marginTop: 2 }}>
        {label}
      </div>
    </div>
  );
}

function Card({ title, color, children }: { title: string; color: string; children: React.ReactNode }) {
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "20px 22px" }}>
      <div className="mono" style={{ fontSize: 11, letterSpacing: "0.08em", color, marginBottom: 18 }}>
        {title}
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 15 }}>{children}</div>
    </div>
  );
}

/** (avg_hr - resting_hr) / (max_hr - resting_hr) * 100 - null unless the athlete has both
 * profile thresholds set (resting_hr is optional; max_hr may not be). */
function hrReservePct(activity: Activity, athlete: Athlete): number | null {
  if (activity.avg_hr == null || athlete.max_hr == null || athlete.resting_hr == null) return null;
  const span = athlete.max_hr - athlete.resting_hr;
  if (span <= 0) return null;
  return Math.round(((activity.avg_hr - athlete.resting_hr) / span) * 100);
}

export function StatsTab({ activity, athlete }: { activity: Activity; athlete: Athlete }) {
  const { data: hrCurve } = useQuery({
    queryKey: ["activity-curve", activity.id, "heartrate"],
    queryFn: () => getCurves(activity.id, "heartrate"),
  });
  const best20MinHr = hrCurve?.points["1200"] != null ? Math.round(hrCurve.points["1200"]) : null;

  const referenceWeightKg = activity.start_weight_kg ?? athlete.weight_kg;
  const avgWkg =
    activity.avg_power != null && referenceWeightKg ? (activity.avg_power / referenceWeightKg).toFixed(2) : null;
  const workKj = activity.avg_power != null ? Math.round((activity.avg_power * activity.moving_time) / 1000) : null;
  const avgSpeedKmh = activity.moving_time > 0 ? ((activity.distance_km / activity.moving_time) * 3600).toFixed(1) : null;

  const hasPowerCard = activity.avg_power != null || activity.max_power != null;
  const hasHrCard = activity.avg_hr != null || activity.max_hr != null;
  const hasSpeedCadenceCard =
    avgSpeedKmh != null || activity.max_speed != null || activity.avg_cadence != null || activity.max_cadence != null;
  const hasElevationCard =
    activity.total_descent != null || activity.elevation_min != null || activity.calories != null;

  if (!hasPowerCard && !hasHrCard && !hasSpeedCadenceCard && !hasElevationCard) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>No extended stats available for this activity.</div>;
  }

  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: 14 }}>
      {hasPowerCard && (
        <Card title="POWER" color="var(--ember)">
          <Stat label="Max Power" value={activity.max_power} unit="w" />
          <Stat label="Avg Watts/kg" value={avgWkg} unit="w/kg" />
          <Stat label="Work" value={workKj?.toLocaleString()} unit="kJ" />
          {activity.avg_left_balance_pct != null && (
            <Stat
              label="L/R Balance"
              value={`${Math.round(activity.avg_left_balance_pct)}/${Math.round(100 - activity.avg_left_balance_pct)}`}
              unit="%"
            />
          )}
        </Card>
      )}
      {hasHrCard && (
        <Card title="HEART RATE" color="#e0442e">
          <Stat label="Max HR" value={activity.max_hr} unit="bpm" />
          <Stat label="Best 20 min" value={best20MinHr} unit="bpm" />
          <Stat label="TRIMP" value={activity.trimp != null ? Math.round(activity.trimp) : null} />
          <Stat label="HR Reserve Avg" value={hrReservePct(activity, athlete)} unit="%" />
        </Card>
      )}
      {hasSpeedCadenceCard && (
        <Card title="SPEED · CADENCE" color="#3d7fd6">
          <Stat label="Avg Speed" value={avgSpeedKmh} unit="km/h" />
          <Stat label="Max Speed" value={activity.max_speed?.toFixed(1)} unit="km/h" />
          <Stat label="Avg Cadence" value={activity.avg_cadence} unit="rpm" />
          <Stat label="Max Cadence" value={activity.max_cadence} unit="rpm" />
        </Card>
      )}
      {hasElevationCard && (
        <Card title="ELEVATION · ENERGY" color="#2fa66a">
          <Stat label="Total Descent" value={activity.total_descent} unit="m" />
          <Stat
            label="Elev Range"
            value={activity.elevation_min != null && activity.elevation_max != null ? `${activity.elevation_min} to ${activity.elevation_max}` : null}
            unit="m"
          />
          <Stat label="Calories" value={activity.calories?.toLocaleString()} unit="kcal" />
          <Stat label="Elapsed Time" value={formatDuration(activity.moving_time)} />
        </Card>
      )}
    </div>
  );
}
