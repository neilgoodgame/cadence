import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { getStreams } from "../../api/activities";
import type { Activity } from "../../api/types";

function mean(values: (number | null)[]): number | null {
  const present = values.filter((v): v is number => v != null);
  return present.length > 0 ? present.reduce((a, b) => a + b, 0) / present.length : null;
}

function max(values: (number | null)[]): number | null {
  const present = values.filter((v): v is number => v != null);
  return present.length > 0 ? Math.max(...present) : null;
}

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

/** Ambient conditions (Stryd footpod) and body-temperature load (CORE sensor) - both
 * third-party run sensors, so most activities have neither. avg_air_temp/avg_humidity are
 * already aggregated onto the Activity at ingest; core/skin temp aren't (no UI need for a
 * per-activity aggregate until now), so those are computed client-side from the streams
 * endpoint the same way HeatStrainCard already does for heat_strain. */
export function EnvironmentCard({ activity }: { activity: Activity }) {
  const { data } = useQuery({
    queryKey: ["activity-streams-environment", activity.id],
    queryFn: () => getStreams(activity.id, ["core_temp", "skin_temp"], "medium"),
  });

  const core = useMemo(() => {
    if (!data) return null;
    const coreTemp = data.fields.core_temp ?? [];
    const skinTemp = data.fields.skin_temp ?? [];
    const avgCore = mean(coreTemp);
    const maxCore = max(coreTemp);
    const avgSkin = mean(skinTemp);
    if (avgCore == null && avgSkin == null) return null;
    return { avgCore, maxCore, avgSkin };
  }, [data]);

  const hasStryd = activity.avg_air_temp != null || activity.avg_humidity != null;
  if (!hasStryd && !core) {
    return null;
  }

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "20px 22px" }}>
      <div className="mono" style={{ fontSize: 11, letterSpacing: "0.08em", color: "#3d7fd6", marginBottom: 18 }}>
        ENVIRONMENT
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 15 }}>
        {hasStryd && (
          <>
            <Stat label="Air Temp" value={activity.avg_air_temp?.toFixed(1) ?? null} unit="°C" />
            <Stat label="Humidity" value={activity.avg_humidity} unit="%" />
          </>
        )}
        {core && (
          <>
            <Stat label="Avg Core Temp" value={core.avgCore?.toFixed(1) ?? null} unit="°C" />
            <Stat label="Max Core Temp" value={core.maxCore?.toFixed(1) ?? null} unit="°C" />
            <Stat label="Avg Skin Temp" value={core.avgSkin?.toFixed(1) ?? null} unit="°C" />
          </>
        )}
      </div>
    </div>
  );
}
