import { useState, type CSSProperties } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { RouteMap } from "../../components/RouteMap";
import { downloadCourseGpxUrl, recomputeCourseElevation } from "../../api/courses";
import { updateRace } from "../../api/races";
import type { Course, CourseSection, Race } from "../../api/types";
import { CourseSectionsProfile } from "../../components/CourseSectionsProfile";
import { SECTION_COLORS, SECTION_LABELS, formatGrade } from "../../lib/courseSections";
import { LinkCourseDialog } from "./LinkCourseDialog";

function statGrid(course: Course) {
  const climbPerKm = course.distance_km && course.elevation_gain_m != null ? course.elevation_gain_m / course.distance_km : null;
  const cells: { label: string; value: string }[] = [
    { label: "Distance", value: course.distance_km != null ? `${course.distance_km.toFixed(1)} km` : "–" },
    { label: "Gain", value: course.elevation_gain_m != null ? `+${course.elevation_gain_m.toFixed(0)} m` : "–" },
    { label: "Loss", value: course.elevation_loss_m != null ? `-${course.elevation_loss_m.toFixed(0)} m` : "–" },
    { label: "Highest", value: course.elevation_max_m != null ? `${course.elevation_max_m.toFixed(0)} m` : "–" },
    { label: "Lowest", value: course.elevation_min_m != null ? `${course.elevation_min_m.toFixed(0)} m` : "–" },
    { label: "Climb/km", value: climbPerKm != null ? `${climbPerKm.toFixed(0)} m/km` : "–" },
  ];
  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 12, marginTop: 14 }}>
      {cells.map((c) => (
        <div key={c.label}>
          <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.05em", textTransform: "uppercase", color: "var(--ink3)" }}>{c.label}</div>
          <div className="mono" style={{ fontSize: 15, fontWeight: 700, marginTop: 1 }}>{c.value}</div>
        </div>
      ))}
    </div>
  );
}


const QUALITY_NOTICE: Record<"missing" | "sparse", string> = {
  missing: "This course's GPX has no elevation data, so climbs and flats can't be shown. Try a GPX from a route planner, or create the course from your activity.",
  sparse: "This course's GPX has too few points to show climbs and flats reliably - hills between points are lost. Try a GPX from a route planner, or create the course from your activity.",
};

function ElevationNotice({ course }: { course: Course }) {
  const queryClient = useQueryClient();
  const rebuild = useMutation({
    mutationFn: () => recomputeCourseElevation(course.id),
    onSuccess: (updated) => queryClient.setQueryData(["course", course.id], updated),
  });
  if (course.elevation_quality === "ok") return null;
  return (
    <div style={{ marginTop: 16, fontSize: 12.5, color: "var(--ink2)", background: "var(--elev)", borderRadius: 8, padding: "10px 12px" }}>
      {QUALITY_NOTICE[course.elevation_quality]}
      <div style={{ marginTop: 6 }}>
        <button
          onClick={() => rebuild.mutate()}
          disabled={rebuild.isPending}
          style={{ border: "none", background: "none", padding: 0, color: "var(--ember)", fontSize: 12.5, fontWeight: 600, cursor: "pointer" }}
        >
          {rebuild.isPending ? "Rebuilding…" : "Rebuild from the source file"}
        </button>
        {rebuild.isError && <span style={{ marginLeft: 8, color: "#e0442e" }}>{(rebuild.error as Error).message}</span>}
      </div>
    </div>
  );
}

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

function ElevationProfile({ course }: { course: Course }) {
  const [showSections, setShowSections] = useState(false);
  const climbs = course.sections.filter((s) => s.type === "climb" || s.type === "steep_climb");
  return (
    <div style={{ marginTop: 16 }}>
      <CourseSectionsProfile profile={course.elevation_profile} sections={course.sections} />
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
      {course.sections.length > 0 && (
        <>
          <button
            onClick={() => setShowSections(!showSections)}
            style={{ marginTop: 10, border: "none", background: "none", padding: 0, color: "var(--ink2)", fontSize: 12, fontWeight: 600, cursor: "pointer" }}
          >
            {showSections ? "Hide sections" : `Show all ${course.sections.length} sections`}
          </button>
          {showSections && <SectionTable sections={course.sections} />}
        </>
      )}
    </div>
  );
}

function RacedOnThisCourse({ course, currentRaceId }: { course: Course; currentRaceId: string }) {
  if (course.races.length === 0) return null;
  const bestS = Math.min(...course.races.filter((r) => r.result_s != null).map((r) => r.result_s!));
  const byYear = new Map<number, typeof course.races>();
  for (const r of course.races) {
    const year = new Date(r.date + "T00:00:00").getFullYear();
    byYear.set(year, [...(byYear.get(year) ?? []), r]);
  }
  const years = [...byYear.keys()].sort((a, b) => b - a);

  return (
    <div style={{ marginTop: 16 }}>
      <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ink3)", marginBottom: 6 }}>
        Raced on this course
      </div>
      {years.map((year) => (
        <div key={year} style={{ marginBottom: 6 }}>
          <div style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 2 }}>{year}</div>
          {byYear.get(year)!.map((r) => {
            const isBest = r.result_s === bestS;
            const diff = r.result_s != null ? r.result_s - bestS : null;
            return (
              <div
                key={r.id}
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  fontSize: 12.5,
                  padding: "4px 6px",
                  borderRadius: 6,
                  background: r.id === currentRaceId ? "var(--ember)22" : "transparent",
                }}
              >
                <span>{r.name}</span>
                {isBest ? (
                  <span style={{ fontSize: 9.5, fontWeight: 700, color: "#2fa66a", border: "1px solid #2fa66a", borderRadius: 4, padding: "0 4px" }}>
                    Course best
                  </span>
                ) : diff != null ? (
                  <span className="mono" style={{ color: "var(--ink3)" }}>+{Math.round(diff)}s</span>
                ) : null}
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}

/** README §4.2.4 "Course" card - the map, stats, climb profile, and link/change/unlink
 * actions. Renders "No course linked" when the race has no `course_id`. */
export function CourseCard({ race, course }: { race: Race; course: Course | null }) {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);

  const unlinkMutation = useMutation({
    mutationFn: () => updateRace(race.id, { course_id: null }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["race", race.id] }),
  });

  async function downloadGpx() {
    if (!course) return;
    const url = await downloadCourseGpxUrl(course.id);
    const a = document.createElement("a");
    a.href = url;
    a.download = course.gpx_filename || `${course.name}.gpx`;
    a.click();
    URL.revokeObjectURL(url);
  }

  if (!course) {
    return (
      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
        <div style={{ fontSize: 13, color: "var(--ink3)", marginBottom: 10 }}>No course linked.</div>
        <button
          onClick={() => setDialogOpen(true)}
          style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", cursor: "pointer" }}
        >
          Link course
        </button>
        {dialogOpen && (
          <LinkCourseDialog raceId={race.id} raceActivityId={race.activity_id} raceDistanceKm={race.distance_km} onClose={() => setDialogOpen(false)} />
        )}
      </div>
    );
  }

  const warn = race.distance_km != null && course.distance_km != null && Math.abs(course.distance_km - race.distance_km) / race.distance_km > 0.01;

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 10, flexWrap: "wrap" }}>
        <div>
          <div style={{ fontSize: 16, fontWeight: 700 }}>{course.name}</div>
          {course.location_name && <div style={{ fontSize: 12.5, color: "var(--ink3)" }}>{course.location_name}</div>}
        </div>
        <span
          className="mono"
          style={{ fontSize: 9.5, fontWeight: 700, padding: "2px 8px", borderRadius: 5, border: "1px solid var(--line)", color: "var(--ink3)" }}
        >
          {course.source === "gpx"
            ? `GPX · ${course.gpx_filename} · ${course.created_at.slice(0, 10)}`
            : "ACTIVITY · From the GPS track of an activity"}
        </span>
      </div>

      {warn && (
        <div style={{ fontSize: 12, color: "#f0a02e", marginTop: 8 }}>
          This course's distance differs from the race's by more than 1%.
        </div>
      )}

      {course.route_polyline.length > 1 && (
        <div style={{ marginTop: 14 }}>
          <RouteMap
            coordinates={course.route_polyline.map(([lat, lng]) => [lng, lat])}
            height={260}
            startFinishLoop={course.is_loop}
            kmMarkers
          />
        </div>
      )}

      {statGrid(course)}
      <ElevationProfile course={course} />
      <ElevationNotice course={course} />
      <RacedOnThisCourse course={course} currentRaceId={race.id} />

      <div style={{ display: "flex", gap: 10, marginTop: 16 }}>
        <button onClick={downloadGpx} style={{ border: "1px solid var(--line)", background: "none", borderRadius: 8, fontSize: 12.5, fontWeight: 600, padding: "6px 12px", cursor: "pointer", color: "var(--ink2)" }}>
          Download GPX
        </button>
        <button onClick={() => setDialogOpen(true)} style={{ border: "1px solid var(--line)", background: "none", borderRadius: 8, fontSize: 12.5, fontWeight: 600, padding: "6px 12px", cursor: "pointer", color: "var(--ink2)" }}>
          Change
        </button>
        <button
          onClick={() => {
            if (window.confirm("Unlink this course from the race?")) unlinkMutation.mutate();
          }}
          style={{ border: "1px solid var(--line)", background: "none", borderRadius: 8, fontSize: 12.5, fontWeight: 600, padding: "6px 12px", cursor: "pointer", color: "#e0442e" }}
        >
          Unlink
        </button>
      </div>

      {dialogOpen && (
        <LinkCourseDialog raceId={race.id} raceActivityId={race.activity_id} raceDistanceKm={race.distance_km} onClose={() => setDialogOpen(false)} />
      )}
    </div>
  );
}
