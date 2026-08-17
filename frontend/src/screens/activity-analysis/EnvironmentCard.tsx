import { useMemo, useState, type CSSProperties } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getStreams, updateActivity } from "../../api/activities";
import type { Activity } from "../../api/types";

const numberInputStyle: CSSProperties = {
  fontSize: 13,
  padding: "3px 8px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  color: "var(--ink)",
  width: 90,
};

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
 * endpoint the same way HeatStrainCard already does for heat_strain.
 *
 * When neither sensor supplied air temp/humidity, they can be entered by hand - the API
 * accepts avg_air_temp/avg_humidity on PATCH for exactly this case, and silently ignores
 * the write once real Stryd data exists (see openapi.yaml's updateActivity description),
 * so there's no manual-vs-computed flag to track here beyond hasStryd itself. */
export function EnvironmentCard({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
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

  const [editing, setEditing] = useState(false);
  const [airTempInput, setAirTempInput] = useState("");
  const [humidityInput, setHumidityInput] = useState("");
  const [error, setError] = useState<string | null>(null);

  const saveMutation = useMutation({
    mutationFn: (patch: { avg_air_temp: number | null; avg_humidity: number | null }) =>
      updateActivity(activity.id, patch),
    onSuccess: (updated) => {
      queryClient.setQueryData(["activity", activity.id], updated);
      setEditing(false);
    },
  });

  function startEditing() {
    setAirTempInput("");
    setHumidityInput("");
    setError(null);
    setEditing(true);
  }

  function commit() {
    const airTemp = airTempInput.trim() === "" ? null : Number(airTempInput);
    const humidity = humidityInput.trim() === "" ? null : Number(humidityInput);
    if (airTemp != null && (Number.isNaN(airTemp) || airTemp < -60 || airTemp > 60)) {
      setError("Air temp should be between -60 and 60°C.");
      return;
    }
    if (humidity != null && (Number.isNaN(humidity) || humidity < 0 || humidity > 100)) {
      setError("Humidity should be between 0 and 100%.");
      return;
    }
    if (airTemp == null && humidity == null) {
      setEditing(false);
      return;
    }
    setError(null);
    saveMutation.mutate({ avg_air_temp: airTemp, avg_humidity: humidity != null ? Math.round(humidity) : null });
  }

  if (!hasStryd && !core && !editing) {
    return (
      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "20px 22px" }}>
        <div className="mono" style={{ fontSize: 11, letterSpacing: "0.08em", color: "#3d7fd6", marginBottom: 12 }}>
          ENVIRONMENT
        </div>
        <button
          type="button"
          onClick={startEditing}
          style={{
            fontSize: 13,
            fontWeight: 600,
            padding: "6px 12px",
            borderRadius: 8,
            border: "1px solid var(--line)",
            background: "none",
            color: "var(--ink2)",
            cursor: "pointer",
          }}
        >
          + Add temperature & humidity
        </button>
      </div>
    );
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
        {!hasStryd && editing && (
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            <div style={{ display: "flex", gap: 8 }}>
              <input
                type="number"
                placeholder="Air temp °C"
                value={airTempInput}
                onChange={(e) => setAirTempInput(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && commit()}
                autoFocus
                style={numberInputStyle}
              />
              <input
                type="number"
                placeholder="Humidity %"
                value={humidityInput}
                onChange={(e) => setHumidityInput(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && commit()}
                style={numberInputStyle}
              />
            </div>
            {error && <div style={{ fontSize: 11, color: "var(--danger, #c0392b)" }}>{error}</div>}
            <div style={{ display: "flex", gap: 8 }}>
              <button
                type="button"
                onClick={commit}
                disabled={saveMutation.isPending}
                style={{
                  fontSize: 12,
                  fontWeight: 700,
                  padding: "4px 12px",
                  borderRadius: 8,
                  border: "none",
                  background: "var(--ember)",
                  color: "#fff",
                  cursor: "pointer",
                  opacity: saveMutation.isPending ? 0.5 : 1,
                }}
              >
                {saveMutation.isPending ? "…" : "Save"}
              </button>
              <button
                type="button"
                onClick={() => setEditing(false)}
                style={{ fontSize: 12, border: "none", background: "none", color: "var(--ink3)", cursor: "pointer" }}
              >
                Cancel
              </button>
            </div>
          </div>
        )}
        {!hasStryd && !editing && (
          <button
            type="button"
            onClick={startEditing}
            style={{ fontSize: 12, fontWeight: 600, padding: 0, border: "none", background: "none", color: "var(--ink3)", cursor: "pointer", textAlign: "left" }}
          >
            + Add temperature & humidity
          </button>
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
