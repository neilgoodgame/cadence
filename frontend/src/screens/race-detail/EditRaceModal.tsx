import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listTags } from "../../api/activities";
import { listCourses } from "../../api/courses";
import { updateRace } from "../../api/races";
import { ApiError, type Race, type RacePriority } from "../../api/types";
import { useAuth } from "../../auth/AuthContext";
import { defaultPhaseWeeks, matchDistanceBucket, weeksUntilRace } from "../../lib/raceDistanceBuckets";

interface BlockPlan {
  block: string;
  base: string;
  build: string;
  peak: string;
  taper: string;
}

const SPORTS = ["run", "bike", "swim", "multisport"];
const PRIORITIES: { key: RacePriority; label: string; hint: string }[] = [
  { key: "A", label: "A", hint: "The goal race - gets its own training block" },
  { key: "B", label: "B", hint: "A tune-up, hard effort but not the main goal" },
  { key: "C", label: "C", hint: "Low-key, run as training" },
];

const fieldStyle: React.CSSProperties = {
  width: "100%",
  padding: "8px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  color: "var(--ink)",
  fontSize: 13,
  boxSizing: "border-box",
};

/** Opened from the race detail Header's "Edit" button - same field set as AddRaceModal, plus
 * the result/results-url fields that only make sense once a race has happened. */
export function EditRaceModal({ race, onClose }: { race: Race; onClose: () => void }) {
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const [name, setName] = useState(race.name);
  const [date, setDate] = useState(race.date);
  const [sport, setSport] = useState(race.sport);
  const [priority, setPriority] = useState<RacePriority>(race.priority);
  const [courseId, setCourseId] = useState(race.course_id ?? "");
  const [keySessionTagId, setKeySessionTagId] = useState(race.key_session_tag_id ?? "");
  const [distanceKm, setDistanceKm] = useState(race.distance_km?.toString() ?? "");
  const [goalTime, setGoalTime] = useState(race.goal_time ?? "");
  const [resultTime, setResultTime] = useState(race.result_time ?? "");
  const [targetPower, setTargetPower] = useState(race.target_power?.toString() ?? "");
  const [url, setUrl] = useState(race.url ?? "");
  const [resultsUrl, setResultsUrl] = useState(race.results_url ?? "");
  const [notes, setNotes] = useState(race.notes);

  // A races only - starts from the race's own already-saved plan if it has one (so an existing
  // explicit plan is never silently overridden by a later distance edit); otherwise null, so
  // the displayed values below stay derived from the athlete's per-distance-bucket defaults
  // as distanceKm changes, with no effect/setState sync, until the athlete edits a field here.
  const [manualPlan, setManualPlan] = useState<BlockPlan | null>(
    race.block_weeks_requested != null
      ? {
          block: String(race.block_weeks_requested),
          base: String(race.base_weeks_requested ?? ""),
          build: String(race.build_weeks_requested ?? ""),
          peak: String(race.peak_weeks_requested ?? ""),
          taper: String(race.taper_weeks_requested ?? ""),
        }
      : null,
  );

  const bucket = matchDistanceBucket(distanceKm ? parseFloat(distanceKm) : null);
  const fromBucket = bucket != null ? user?.race_block_week_defaults?.[bucket] : undefined;
  const defaultBlockWeeks = fromBucket ?? user?.race_block_weeks ?? 16;
  const defaultPhases = defaultPhaseWeeks(defaultBlockWeeks);

  const blockWeeks = manualPlan?.block ?? String(defaultBlockWeeks);
  const baseWeeks = manualPlan?.base ?? String(defaultPhases.base);
  const buildWeeks = manualPlan?.build ?? String(defaultPhases.build);
  const peakWeeks = manualPlan?.peak ?? String(defaultPhases.peak);
  const taperWeeks = manualPlan?.taper ?? String(defaultPhases.taper);

  const blockWeeksN = parseInt(blockWeeks, 10) || 0;
  const phaseSum = [baseWeeks, buildWeeks, peakWeeks, taperWeeks].reduce((sum, w) => sum + (parseInt(w, 10) || 0), 0);
  const phaseSumValid = priority !== "A" || blockWeeksN === phaseSum;
  const weeksUntil = weeksUntilRace(date);
  const weeksRemainValid = priority !== "A" || weeksUntil === null || blockWeeksN <= weeksUntil;
  const blockPlanValid = phaseSumValid && weeksRemainValid;

  function editPlan(field: keyof BlockPlan, value: string) {
    setManualPlan({ block: blockWeeks, base: baseWeeks, build: buildWeeks, peak: peakWeeks, taper: taperWeeks, [field]: value });
  }

  const { data: coursesData } = useQuery({ queryKey: ["courses", ""], queryFn: () => listCourses() });
  const { data: tagsData } = useQuery({ queryKey: ["tags"], queryFn: () => listTags() });

  const mutation = useMutation({
    mutationFn: () =>
      updateRace(race.id, {
        name,
        date,
        sport: sport || undefined,
        priority,
        course_id: courseId || null,
        distance_km: distanceKm ? parseFloat(distanceKm) : null,
        goal_time: goalTime || null,
        result_time: resultTime || null,
        target_power: targetPower ? parseInt(targetPower, 10) : null,
        url: url || null,
        results_url: resultsUrl || null,
        notes,
        key_session_tag_id: keySessionTagId || null,
        ...(priority === "A"
          ? {
              block_weeks_requested: blockWeeks ? parseInt(blockWeeks, 10) : null,
              base_weeks_requested: baseWeeks ? parseInt(baseWeeks, 10) : null,
              build_weeks_requested: buildWeeks ? parseInt(buildWeeks, 10) : null,
              peak_weeks_requested: peakWeeks ? parseInt(peakWeeks, 10) : null,
              taper_weeks_requested: taperWeeks ? parseInt(taperWeeks, 10) : null,
            }
          : {}),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["race", race.id] });
      queryClient.invalidateQueries({ queryKey: ["races"] });
      onClose();
    },
  });

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ background: "var(--card)", borderRadius: 14, padding: 24, width: 420, maxHeight: "85vh", overflowY: "auto", display: "flex", flexDirection: "column", gap: 16 }}
      >
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Edit race</h2>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Name</div>
          <input value={name} onChange={(e) => setName(e.target.value)} style={fieldStyle} />
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Date</div>
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={fieldStyle} />
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Sport</div>
          <select value={sport} onChange={(e) => setSport(e.target.value)} style={fieldStyle}>
            <option value="">—</option>
            {SPORTS.map((s) => <option key={s} value={s}>{s.charAt(0).toUpperCase() + s.slice(1)}</option>)}
          </select>
        </label>
        <div>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Priority</div>
          <div style={{ display: "flex", gap: 6 }}>
            {PRIORITIES.map((p) => (
              <button
                key={p.key}
                type="button"
                onClick={() => setPriority(p.key)}
                title={p.hint}
                style={{
                  flex: 1,
                  padding: "7px 0",
                  borderRadius: 8,
                  border: `1px solid ${priority === p.key ? "var(--ember)" : "var(--line)"}`,
                  background: priority === p.key ? "var(--ember)22" : "transparent",
                  color: priority === p.key ? "var(--ember)" : "var(--ink2)",
                  fontSize: 13,
                  fontWeight: 700,
                  cursor: "pointer",
                }}
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>

        {priority === "A" && (
          <div>
            <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Training block</div>
            <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}>
              <span style={{ fontSize: 12.5, color: "var(--ink2)" }}>Block length</span>
              <input
                type="number"
                min={1}
                max={52}
                value={blockWeeks}
                onChange={(e) => editPlan("block", e.target.value)}
                style={{ ...fieldStyle, width: 70 }}
              />
              <span style={{ fontSize: 12.5, color: "var(--ink3)" }}>weeks</span>
            </div>
            <div style={{ display: "flex", gap: 6 }}>
              {(
                [
                  { label: "Base", field: "base" as const, value: baseWeeks },
                  { label: "Build", field: "build" as const, value: buildWeeks },
                  { label: "Peak", field: "peak" as const, value: peakWeeks },
                  { label: "Taper", field: "taper" as const, value: taperWeeks },
                ]
              ).map((phase) => (
                <label key={phase.label} style={{ flex: 1 }}>
                  <div style={{ fontSize: 10.5, color: "var(--ink3)", marginBottom: 3, textAlign: "center" }}>{phase.label}</div>
                  <input
                    type="number"
                    min={0}
                    max={52}
                    value={phase.value}
                    onChange={(e) => editPlan(phase.field, e.target.value)}
                    style={{ ...fieldStyle, textAlign: "center", padding: "6px 4px" }}
                  />
                </label>
              ))}
            </div>
            {!phaseSumValid && (
              <div style={{ fontSize: 11.5, color: "#e0442e", marginTop: 6 }}>
                Base+Build+Peak+Taper ({phaseSum}) must equal the block length ({blockWeeksN}).
              </div>
            )}
            {phaseSumValid && !weeksRemainValid && (
              <div style={{ fontSize: 11.5, color: "#e0442e", marginTop: 6 }}>
                Only {weeksUntil} week{weeksUntil === 1 ? "" : "s"} remain before this race - the block length must
                be {weeksUntil} or fewer.
              </div>
            )}
          </div>
        )}

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Course (optional)</div>
          <select value={courseId} onChange={(e) => setCourseId(e.target.value)} style={fieldStyle}>
            <option value="">—</option>
            {(coursesData?.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Key session tag (optional)</div>
          <select value={keySessionTagId} onChange={(e) => setKeySessionTagId(e.target.value)} style={fieldStyle}>
            <option value="">— Use "Key session" tag —</option>
            {(tagsData?.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
          <div style={{ fontSize: 11, color: "var(--ink3)", marginTop: 4 }}>
            Which tag this race's "Key sessions" card pulls from, if you'd rather not share the account-wide "Key session" tag with other races.
          </div>
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Distance km</div>
          <input type="number" step="0.1" value={distanceKm} onChange={(e) => setDistanceKm(e.target.value)} style={fieldStyle} />
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Goal time (optional)</div>
          <input value={goalTime} onChange={(e) => setGoalTime(e.target.value)} placeholder="H:MM:SS" style={fieldStyle} />
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Result time (optional)</div>
          <input value={resultTime} onChange={(e) => setResultTime(e.target.value)} placeholder="H:MM:SS" style={fieldStyle} />
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Target power, watts (optional)</div>
          <input type="number" step="1" value={targetPower} onChange={(e) => setTargetPower(e.target.value)} placeholder="e.g. 250" style={fieldStyle} />
          <div style={{ fontSize: 11, color: "var(--ink3)", marginTop: 4 }}>
            Used as the Splits card's effort reference instead of grade-adjusted pace, for a race run with a power meter. Falls back to the activity's own average power when unset.
          </div>
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Race website (optional)</div>
          <input type="url" value={url} onChange={(e) => setUrl(e.target.value)} style={fieldStyle} />
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Results URL (optional)</div>
          <input type="url" value={resultsUrl} onChange={(e) => setResultsUrl(e.target.value)} style={fieldStyle} />
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Notes (optional)</div>
          <input value={notes} onChange={(e) => setNotes(e.target.value)} style={fieldStyle} />
        </label>

        {mutation.isError && (
          <div style={{ fontSize: 12.5, color: "#e0442e" }}>
            {mutation.error instanceof ApiError ? mutation.error.message : "Something went wrong. Try again."}
          </div>
        )}

        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
          <button onClick={onClose} style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, padding: "8px 16px", cursor: "pointer" }}>
            Cancel
          </button>
          <button
            onClick={() => mutation.mutate()}
            disabled={!name || !date || !blockPlanValid || mutation.isPending}
            style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", cursor: "pointer" }}
          >
            {mutation.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}
