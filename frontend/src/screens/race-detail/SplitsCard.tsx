import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getRaceSplits } from "../../api/races";
import type { RaceSplit, SplitsMode } from "../../api/types";
import { formatDuration, formatPace } from "../../lib/format";
import { defaultSplitsMode } from "./splitsMode";

const MAX_DELTA = 12;

function DeltaBar({ delta }: { delta: number | null }) {
  if (delta == null) return <span style={{ color: "var(--ink3)" }}>–</span>;
  const clamped = Math.max(-MAX_DELTA, Math.min(MAX_DELTA, delta));
  const pct = (Math.abs(clamped) / MAX_DELTA) * 50;
  const faster = delta < 0;
  return (
    <div style={{ width: 90, height: 10, position: "relative", background: "var(--canvas)", borderRadius: 4 }}>
      <div style={{ position: "absolute", left: "50%", top: 0, bottom: 0, width: 1, background: "var(--line)" }} />
      <div
        style={{
          position: "absolute",
          top: 0,
          bottom: 0,
          width: `${pct}%`,
          left: faster ? `${50 - pct}%` : "50%",
          background: faster ? "#2fa66a" : "#e0442e",
          borderRadius: 3,
        }}
      />
    </div>
  );
}

function rowLabel(split: RaceSplit, index: number, mode: SplitsMode): string {
  if (split.label) return split.label;
  if (mode === "laps") return `Lap ${index + 1} · ${split.start_km.toFixed(1)}–${split.end_km.toFixed(1)}`;
  return `${split.end_km.toFixed(mode === "1" ? 0 : 0)} km`;
}

/** README §4.2.6 "Splits" card - shown for a past run race with a result. */
export function SplitsCard({ raceId, distanceKm }: { raceId: string; distanceKm: number | null }) {
  // null until the athlete picks a mode themselves - until then the effective mode is derived
  // from the laps probe below (laps if available, else the distance-based fallback), with no
  // setState-in-effect needed to "apply" that default.
  const [userMode, setUserMode] = useState<SplitsMode | null>(null);

  const lapsProbe = useQuery({ queryKey: ["race-splits", raceId, "laps"], queryFn: () => getRaceSplits(raceId, "laps") });
  const hasLaps = lapsProbe.data?.has_laps ?? true;
  const activeMode = userMode ?? (lapsProbe.data ? defaultSplitsMode(lapsProbe.data.has_laps, distanceKm) : "laps");

  const activeQuery = useQuery({
    queryKey: ["race-splits", raceId, activeMode],
    queryFn: () => getRaceSplits(raceId, activeMode),
    enabled: !!lapsProbe.data,
  });

  const data = activeQuery.data;

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12, flexWrap: "wrap", gap: 10 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>Splits</h2>
        <div style={{ display: "flex", gap: 2, background: "var(--canvas)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
          {(["laps", "1", "5"] as SplitsMode[]).map((m) => (
            <div
              key={m}
              onClick={() => (m !== "laps" || hasLaps) && setUserMode(m)}
              title={m === "laps" && !hasLaps ? "No manual laps on this activity - pick 1 km or 5 km instead." : undefined}
              style={{
                padding: "5px 11px",
                fontSize: 11.5,
                fontWeight: 600,
                borderRadius: 7,
                cursor: m === "laps" && !hasLaps ? "default" : "pointer",
                opacity: m === "laps" && !hasLaps ? 0.4 : 1,
                color: activeMode === m ? "var(--ink)" : "var(--ink3)",
                background: activeMode === m ? "var(--card)" : "transparent",
                boxShadow: activeMode === m ? "0 1px 2px rgba(0,0,0,0.14)" : "none",
              }}
            >
              {m === "laps" ? "Laps" : m === "1" ? "1 km" : "5 km"}
            </div>
          ))}
        </div>
      </div>

      {data && (
        <div style={{ maxHeight: 520, overflowY: "auto" }}>
          <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12.5 }}>
            <thead>
              <tr style={{ position: "sticky", top: 0, background: "var(--card)", textAlign: "left" }}>
                {["Split", "Dist", "Time", "Pace", "Avg HR", "", "Δ"].map((h) => (
                  <th key={h} style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.05em", textTransform: "uppercase", color: "var(--ink3)", padding: "6px 8px", borderBottom: "1px solid var(--line)" }}>
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.splits.map((split, i) => (
                <tr key={i} style={{ borderBottom: "1px solid var(--line)" }}>
                  <td className="mono" style={{ padding: "6px 8px", fontWeight: i === data.splits.length - 1 ? 700 : 400 }}>
                    {rowLabel(split, i, activeMode)}
                  </td>
                  <td className="mono" style={{ padding: "6px 8px" }}>{(split.end_km - split.start_km).toFixed(2)} km</td>
                  <td className="mono" style={{ padding: "6px 8px" }}>{formatDuration(split.time_s)}</td>
                  <td className="mono" style={{ padding: "6px 8px" }}>{split.pace_s_per_km != null ? formatPace(split.pace_s_per_km) : "–"}</td>
                  <td className="mono" style={{ padding: "6px 8px" }}>{split.avg_hr ?? "–"}</td>
                  <td style={{ padding: "6px 8px" }}><DeltaBar delta={split.delta_vs_ref_s_per_km} /></td>
                  <td className="mono" style={{ padding: "6px 8px", color: split.delta_vs_ref_s_per_km == null ? "var(--ink3)" : split.delta_vs_ref_s_per_km < 0 ? "#2fa66a" : "#e0442e" }}>
                    {split.delta_vs_ref_s_per_km != null ? `${split.delta_vs_ref_s_per_km <= 0 ? "-" : "+"}${Math.abs(split.delta_vs_ref_s_per_km).toFixed(0)}s/km` : "–"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
