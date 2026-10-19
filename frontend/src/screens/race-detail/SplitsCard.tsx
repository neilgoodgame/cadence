import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getRaceSplits } from "../../api/races";
import type { Course, SplitsMode } from "../../api/types";
import { formatDuration, formatPace } from "../../lib/format";
import { SECTION_COLORS, formatGrade } from "../../lib/courseSections";
import { defaultSplitsMode, splitRowLabel } from "../../lib/splits";

const MAX_DELTA = 12;
const MAX_POWER_DELTA = 40;

function DeltaBar({ delta, maxDelta = MAX_DELTA }: { delta: number | null; maxDelta?: number }) {
  if (delta == null) return <span style={{ color: "var(--ink3)" }}>–</span>;
  const clamped = Math.max(-maxDelta, Math.min(maxDelta, delta));
  const pct = (Math.abs(clamped) / maxDelta) * 50;
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

/** README §4.2.6 "Splits" card - shown for a past run race with a result. `course` is the
 * same course RaceDetailScreen already fetches for CourseCard - reused here (no extra query)
 * to decide whether the course has elevation sections worth offering as a splits view. */
export function SplitsCard({ raceId, distanceKm, course }: { raceId: string; distanceKm: number | null; course: Course | null }) {
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
  const elevationAvailable = !!course && course.elevation_quality === "ok" && course.sections.length > 0;
  const modes: SplitsMode[] = elevationAvailable ? ["laps", "1", "5", "elevation"] : ["laps", "1", "5"];
  const hasPower = data?.splits.some((s) => s.avg_power != null) ?? false;
  const hasGrade = data?.splits.some((s) => s.avg_grade_pct != null) ?? false;
  // Power already accounts for grade mechanically, so it's preferred over GAP (a pace-based
  // estimate of the same thing) whenever it's available - GAP's column and role in Δ are both
  // dropped in favor of the power-based delta below.
  const showGap = hasGrade && !hasPower;
  const headers = [
    "Split",
    "Dist",
    "Time",
    "Pace",
    ...(showGap ? ["GAP"] : []),
    "Avg HR",
    ...(hasPower ? ["Avg Power"] : []),
    ...(hasGrade ? ["Grade"] : []),
    "",
    "Δ",
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
                    title={
                      h === "Δ"
                        ? hasPower
                          ? `vs ${data.ref_power === "target" ? "target" : "average"} power${data.ref_power_w != null ? ` (${data.ref_power_w}W)` : ""}`
                          : showGap
                            ? "Grade-adjusted vs the reference pace"
                            : undefined
                        : h === "GAP"
                          ? "Grade-adjusted pace"
                          : undefined
                    }
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
                  {showGap && <td className="mono" style={{ padding: "6px 8px" }}>{split.gap_pace_s_per_km != null ? formatPace(split.gap_pace_s_per_km) : "–"}</td>}
                  <td className="mono" style={{ padding: "6px 8px" }}>{split.avg_hr ?? "–"}</td>
                  {hasPower && <td className="mono" style={{ padding: "6px 8px" }}>{split.avg_power != null ? `${split.avg_power}W` : "–"}</td>}
                  {hasGrade && <td className="mono" style={{ padding: "6px 8px" }}>{split.avg_grade_pct != null ? formatGrade(split.avg_grade_pct) : "–"}</td>}
                  {hasPower ? (
                    <>
                      {/* Higher power than reference = more effort = "ahead", same visual
                          meaning as a faster (negative) pace delta - negate so DeltaBar's
                          faster-is-green convention lines up. */}
                      <td style={{ padding: "6px 8px" }}>
                        <DeltaBar delta={split.delta_power_w != null ? -split.delta_power_w : null} maxDelta={MAX_POWER_DELTA} />
                      </td>
                      <td className="mono" style={{ padding: "6px 8px", color: split.delta_power_w == null ? "var(--ink3)" : split.delta_power_w >= 0 ? "#2fa66a" : "#e0442e" }}>
                        {split.delta_power_w != null ? `${split.delta_power_w >= 0 ? "+" : "-"}${Math.abs(split.delta_power_w).toFixed(0)}W` : "–"}
                      </td>
                    </>
                  ) : (
                    <>
                      <td style={{ padding: "6px 8px" }}><DeltaBar delta={split.delta_vs_ref_s_per_km} /></td>
                      <td className="mono" style={{ padding: "6px 8px", color: split.delta_vs_ref_s_per_km == null ? "var(--ink3)" : split.delta_vs_ref_s_per_km < 0 ? "#2fa66a" : "#e0442e" }}>
                        {split.delta_vs_ref_s_per_km != null ? `${split.delta_vs_ref_s_per_km <= 0 ? "-" : "+"}${Math.abs(split.delta_vs_ref_s_per_km).toFixed(0)}s/km` : "–"}
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
