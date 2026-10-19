import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getActivitySections, getActivitySplits } from "../../api/activities";
import type { Activity, SplitsMode } from "../../api/types";
import { SECTION_COLORS, formatGrade } from "../../lib/courseSections";
import { formatDuration, formatPace } from "../../lib/format";
import { defaultSplitsMode, splitRowLabel } from "../../lib/splits";

/** Like race-detail/SplitsCard, but for a plain activity (GET /v1/activities/{id}/splits) - no
 * goal/target reference, so no Δ column. The "elevation" mode uses the activity's own recorded
 * elevation (the same calculation ActivitySectionsCard shows above it), not a linked course. */
export function ActivitySplitsCard({ activity }: { activity: Activity }) {
  const [userMode, setUserMode] = useState<SplitsMode | null>(null);

  // Same queryKey ActivitySectionsCard uses - react-query dedupes this to one request when
  // both cards are mounted, so this doesn't cost an extra round trip.
  const sectionsQuery = useQuery({
    queryKey: ["activity-sections", activity.id],
    queryFn: () => getActivitySections(activity.id),
  });
  const elevationAvailable = sectionsQuery.data?.elevation_quality === "ok" && sectionsQuery.data.sections.length > 0;

  const lapsProbe = useQuery({ queryKey: ["activity-splits", activity.id, "laps"], queryFn: () => getActivitySplits(activity.id, "laps") });
  const hasLaps = lapsProbe.data?.has_laps ?? true;
  const activeMode = userMode ?? (lapsProbe.data ? defaultSplitsMode(lapsProbe.data.has_laps, activity.distance_km) : "laps");

  const activeQuery = useQuery({
    queryKey: ["activity-splits", activity.id, activeMode],
    queryFn: () => getActivitySplits(activity.id, activeMode),
    enabled: !!lapsProbe.data,
  });

  const data = activeQuery.data;
  const modes: SplitsMode[] = elevationAvailable ? ["laps", "1", "5", "elevation"] : ["laps", "1", "5"];
  const hasPower = data?.splits.some((s) => s.avg_power != null) ?? false;
  const hasGrade = data?.splits.some((s) => s.avg_grade_pct != null) ?? false;
  const headers = [
    "Split",
    "Dist",
    "Time",
    "Pace",
    ...(hasGrade ? ["GAP"] : []),
    "Avg HR",
    ...(hasPower ? ["Avg Power"] : []),
    ...(hasGrade ? ["Grade"] : []),
  ];

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12, flexWrap: "wrap", gap: 10 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>Splits</h2>
        <div style={{ display: "flex", gap: 2, background: "var(--canvas)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
          {modes.map((m) => (
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
              {m === "laps" ? "Laps" : m === "1" ? "1 km" : m === "5" ? "5 km" : "Elevation"}
            </div>
          ))}
        </div>
      </div>

      {data && (
        <div style={{ maxHeight: 520, overflowY: "auto" }}>
          <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12.5 }}>
            <thead>
              <tr style={{ position: "sticky", top: 0, background: "var(--card)", textAlign: "left" }}>
                {headers.map((h) => (
                  <th
                    key={h}
                    title={h === "GAP" ? "Grade-adjusted pace" : undefined}
                    style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.05em", textTransform: "uppercase", color: "var(--ink3)", padding: "6px 8px", borderBottom: "1px solid var(--line)" }}
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.splits.map((split, i) => (
                <tr key={i} style={{ borderBottom: "1px solid var(--line)" }}>
                  <td className="mono" style={{ padding: "6px 8px", fontWeight: i === data.splits.length - 1 ? 700 : 400 }}>
                    {split.section_type && (
                      <span style={{ display: "inline-block", width: 8, height: 8, borderRadius: 2, marginRight: 6, background: SECTION_COLORS[split.section_type] }} />
                    )}
                    {splitRowLabel(split, i, activeMode)}
                  </td>
                  <td className="mono" style={{ padding: "6px 8px" }}>{(split.end_km - split.start_km).toFixed(2)} km</td>
                  <td className="mono" style={{ padding: "6px 8px" }}>{formatDuration(split.time_s)}</td>
                  <td className="mono" style={{ padding: "6px 8px" }}>{split.pace_s_per_km != null ? formatPace(split.pace_s_per_km) : "–"}</td>
                  {hasGrade && <td className="mono" style={{ padding: "6px 8px" }}>{split.gap_pace_s_per_km != null ? formatPace(split.gap_pace_s_per_km) : "–"}</td>}
                  <td className="mono" style={{ padding: "6px 8px" }}>{split.avg_hr ?? "–"}</td>
                  {hasPower && <td className="mono" style={{ padding: "6px 8px" }}>{split.avg_power != null ? `${split.avg_power}W` : "–"}</td>}
                  {hasGrade && <td className="mono" style={{ padding: "6px 8px" }}>{split.avg_grade_pct != null ? formatGrade(split.avg_grade_pct) : "–"}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
