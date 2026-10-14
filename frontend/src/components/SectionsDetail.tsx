import { useState, type CSSProperties } from "react";
import type { CourseSection } from "../api/types";
import { SECTION_COLORS, SECTION_LABELS, formatGrade } from "../lib/courseSections";
import { CourseSectionsProfile } from "./CourseSectionsProfile";

function SectionTable({ sections }: { sections: CourseSection[] }) {
  const th: CSSProperties = { textAlign: "right", padding: "4px 6px", fontWeight: 600, color: "var(--ink3)", fontSize: 10.5, textTransform: "uppercase", letterSpacing: "0.04em" };
  const td: CSSProperties = { textAlign: "right", padding: "4px 6px" };
  return (
    <div style={{ overflowX: "auto", marginTop: 8 }}>
      <table className="mono" style={{ width: "100%", borderCollapse: "collapse", fontSize: 12 }}>
        <thead>
          <tr style={{ borderBottom: "1px solid var(--line)" }}>
            <th style={{ ...th, textAlign: "left" }}>Type</th>
            <th style={th}>km</th>
            <th style={th}>Length</th>
            <th style={th}>Gain / loss</th>
            <th style={th}>Avg</th>
            <th style={th}>Steepest 100 m</th>
          </tr>
        </thead>
        <tbody>
          {sections.map((s, i) => (
            <tr key={i} style={{ borderBottom: "1px solid var(--line)" }}>
              <td style={{ ...td, textAlign: "left", whiteSpace: "nowrap" }}>
                <span style={{ display: "inline-block", width: 8, height: 8, borderRadius: 2, marginRight: 6, background: SECTION_COLORS[s.type] }} />
                {SECTION_LABELS[s.type]}
              </td>
              <td style={td}>{s.start_km.toFixed(1)}–{s.end_km.toFixed(1)}</td>
              <td style={td}>{s.length_m >= 1000 ? `${(s.length_m / 1000).toFixed(2)} km` : `${s.length_m} m`}</td>
              <td style={td}>
                {s.gain_m ? `+${s.gain_m}` : "0"} / {s.loss_m ? `−${s.loss_m}` : "0"} m
              </td>
              <td style={{ ...td, fontWeight: 700 }}>{formatGrade(s.avg_grade_pct)}</td>
              <td style={td}>{formatGrade(s.steepest_100m_pct)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** The sections profile plus a chip per climb and a toggleable table of every section - shared
 * by the race page's Course card and the activity page's Sections card. */
export function SectionsDetail({ profile, sections }: { profile: [number, number][]; sections: CourseSection[] }) {
  const [showSections, setShowSections] = useState(false);
  const climbs = sections.filter((s) => s.type === "climb" || s.type === "steep_climb");
  return (
    <div>
      <CourseSectionsProfile profile={profile} sections={sections} />
      {climbs.length > 0 && (
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 8 }}>
          {climbs.map((c, i) => (
            <span key={i} style={{ fontSize: 11, color: "var(--ink2)", background: "var(--canvas)", borderRadius: 20, padding: "3px 9px", display: "inline-flex", alignItems: "center", gap: 5 }}>
              <span style={{ width: 7, height: 7, borderRadius: 2, background: SECTION_COLORS[c.type] }} />
              C{i + 1} · km {c.start_km.toFixed(1)}–{c.end_km.toFixed(1)} · +{c.gain_m} m · {c.avg_grade_pct.toFixed(1)}%
              {c.type === "steep_climb" && " · steep"}
            </span>
          ))}
        </div>
      )}
      {sections.length > 0 && (
        <>
          <button
            onClick={() => setShowSections(!showSections)}
            style={{ marginTop: 10, border: "none", background: "none", padding: 0, color: "var(--ink2)", fontSize: 12, fontWeight: 600, cursor: "pointer" }}
          >
            {showSections ? "Hide sections" : `Show all ${sections.length} sections`}
          </button>
          {showSections && <SectionTable sections={sections} />}
        </>
      )}
    </div>
  );
}
