import { scaleLinear } from "d3-scale";
import type { CourseSection } from "../api/types";
import { SECTION_COLORS, SECTION_LABELS, SECTION_TYPES, formatGrade, profileSlice, summarizeSections } from "../lib/courseSections";

const W = 1000;
/** Width reserved on the left, in the chart's own coordinate space, for the y-axis tick labels. */
const AXIS_W = 40;

/** A course's elevation profile with each section filled in its grade colour, a legend of the
 * section types present, and a flat / climbing / descending summary. Shared by the race
 * page's Course card and the Preferences › Courses preview. */
export function CourseSectionsProfile({
  profile,
  sections,
  height = 110,
}: {
  profile: [number, number][];
  sections: CourseSection[];
  height?: number;
}) {
  if (profile.length < 2) return null;
  const H = height;
  const plotW = W - AXIS_W;
  const maxKm = profile[profile.length - 1][0];
  const minEle = Math.min(...profile.map((p) => p[1]));
  const maxEle = Math.max(...profile.map((p) => p[1]));
  const x = (km: number) => (km / maxKm) * plotW;
  const y = scaleLinear()
    .domain([minEle, Math.max(maxEle, minEle + 1)])
    .range([H, 0]);
  const yTicks = y.ticks(4);
  const line = profile.map((p, i) => `${i === 0 ? "M" : "L"} ${x(p[0])} ${y(p[1])}`).join(" ");
  const present = SECTION_TYPES.filter((t) => sections.some((s) => s.type === t));
  const summary = summarizeSections(sections);

  return (
    <div>
      {present.length > 0 && (
        <div style={{ display: "flex", gap: 12, flexWrap: "wrap", marginBottom: 6, fontSize: 11, color: "var(--ink2)" }}>
          {present.map((t) => (
            <span key={t} style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
              <span style={{ width: 9, height: 9, borderRadius: 2, background: SECTION_COLORS[t] }} />
              {SECTION_LABELS[t]}
            </span>
          ))}
        </div>
      )}
      <svg
        width="100%"
        viewBox={`0 0 ${W} ${H + 18}`}
        style={{ display: "block" }}
        role="img"
        aria-label="Elevation profile coloured by section grade"
      >
        <g transform={`translate(${AXIS_W},0)`}>
          {yTicks.map((tick) => (
            <g key={tick}>
              <line x1={0} x2={plotW} y1={y(tick)} y2={y(tick)} stroke="var(--line)" strokeDasharray="3,3" />
              <text x={-8} y={y(tick)} textAnchor="end" dominantBaseline="middle" fontSize={10} fill="var(--ink3)" className="mono">
                {`${tick}m`}
              </text>
            </g>
          ))}
          {sections.map((s, i) => {
            const pts = profileSlice(profile, s.start_km, Math.min(s.end_km, maxKm));
            if (pts.length < 2) return null;
            const d = `M ${x(pts[0][0])} ${H} ${pts.map(([km, e]) => `L ${x(km)} ${y(e)}`).join(" ")} L ${x(pts[pts.length - 1][0])} ${H} Z`;
            return (
              <path key={i} d={d} fill={SECTION_COLORS[s.type]} fillOpacity={0.5}>
                <title>
                  {SECTION_LABELS[s.type]} · km {s.start_km.toFixed(1)}–{s.end_km.toFixed(1)} · {formatGrade(s.avg_grade_pct)}
                </title>
              </path>
            );
          })}
          {sections.length === 0 && <path d={`${line} L ${x(maxKm)} ${H} L ${x(0)} ${H} Z`} fill="var(--ember)22" />}
          <path d={line} fill="none" stroke={sections.length > 0 ? "var(--ink2)" : "var(--ember)"} strokeWidth={1.5} />
        </g>
      </svg>
      {sections.length > 0 && (
        <div className="mono" style={{ display: "flex", gap: 14, flexWrap: "wrap", marginTop: 6, fontSize: 12, color: "var(--ink2)" }}>
          <span>{summary.flatKm.toFixed(1)} km flat</span>
          <span>{summary.climbingKm.toFixed(1)} km climbing</span>
          <span>{summary.descendingKm.toFixed(1)} km descending</span>
        </div>
      )}
    </div>
  );
}
