import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listTags } from "../../api/activities";
import { listCourses } from "../../api/courses";
import { createRace } from "../../api/races";
import type { RacePriority } from "../../api/types";

const SPORTS = ["run", "bike", "swim", "multisport"];

const PRIORITIES: { key: RacePriority; label: string; hint: string }[] = [
  { key: "A", label: "A", hint: "The goal race - gets its own training block" },
  { key: "B", label: "B", hint: "A tune-up, hard effort but not the main goal" },
  { key: "C", label: "C", hint: "Low-key, run as training" },
];

const RUN_PRESETS: { label: string; km: number }[] = [
  { label: "5K", km: 5 },
  { label: "10K", km: 10 },
  { label: "10 Mile", km: 16.0934 },
  { label: "Half", km: 21.0975 },
  { label: "Marathon", km: 42.195 },
  { label: "50K", km: 50 },
  { label: "100K", km: 100 },
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

export function AddRaceModal({
  date,
  initialSport = "",
  // Inclusive - matches the native <input type="date"> max attribute directly (unlike
  // ScheduleModal's exclusive maxDate, a race on the expiry day itself still refreshes the
  // threshold in time).
  maxDate,
  onClose,
}: {
  date: string;
  initialSport?: string;
  maxDate?: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [raceDate, setRaceDate] = useState(date);
  const [sport, setSport] = useState(initialSport);
  const [priority, setPriority] = useState<RacePriority>("A");
  const [courseId, setCourseId] = useState("");
  const [keySessionTagId, setKeySessionTagId] = useState("");
  const [distanceKm, setDistanceKm] = useState("");
  const [goalTime, setGoalTime] = useState("");
  const [url, setUrl] = useState("");
  const [notes, setNotes] = useState("");

  const { data: coursesData } = useQuery({ queryKey: ["courses", ""], queryFn: () => listCourses() });
  const { data: tagsData } = useQuery({ queryKey: ["tags"], queryFn: () => listTags() });

  const mutation = useMutation({
    mutationFn: () =>
      createRace({
        name,
        date: raceDate,
        sport: sport || undefined,
        priority,
        course_id: courseId || null,
        distance_km: distanceKm ? parseFloat(distanceKm) : null,
        goal_time: goalTime || null,
        url: url || null,
        notes,
        key_session_tag_id: keySessionTagId || null,
      }),
    onSuccess: () => {
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
        style={{ background: "var(--card)", borderRadius: 14, padding: 24, width: 420, display: "flex", flexDirection: "column", gap: 16 }}
      >
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Add race</h2>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Name</div>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. London Marathon" style={fieldStyle} />
        </label>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Date</div>
          <input type="date" value={raceDate} max={maxDate} onChange={(e) => setRaceDate(e.target.value)} style={fieldStyle} />
        </label>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Sport (optional)</div>
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
          <div style={{ fontSize: 11, color: "var(--ink3)", marginTop: 4 }}>
            {PRIORITIES.find((p) => p.key === priority)?.hint}
          </div>
        </div>

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
        </label>

        <div>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Distance km (optional)</div>
          {sport === "run" && (
            <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginBottom: 8 }}>
              {RUN_PRESETS.map((p) => (
                <button
                  key={p.label}
                  type="button"
                  onClick={() => setDistanceKm(String(p.km))}
                  style={{
                    padding: "4px 10px",
                    borderRadius: 20,
                    border: `1px solid ${distanceKm === String(p.km) ? "var(--ember)" : "var(--line)"}`,
                    background: distanceKm === String(p.km) ? "var(--ember)22" : "transparent",
                    color: distanceKm === String(p.km) ? "var(--ember)" : "var(--ink2)",
                    fontSize: 12,
                    fontWeight: 600,
                    cursor: "pointer",
                  }}
                >
                  {p.label}
                </button>
              ))}
            </div>
          )}
          <input type="number" step="0.1" value={distanceKm} onChange={(e) => setDistanceKm(e.target.value)} placeholder="km" style={fieldStyle} />
        </div>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Goal time (optional)</div>
          <input value={goalTime} onChange={(e) => setGoalTime(e.target.value)} placeholder="H:MM:SS" style={fieldStyle} />
        </label>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Race website (optional)</div>
          <input type="url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://…" style={fieldStyle} />
        </label>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Notes (optional)</div>
          <input value={notes} onChange={(e) => setNotes(e.target.value)} style={fieldStyle} />
        </label>

        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
          <button onClick={onClose} style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, padding: "8px 16px", cursor: "pointer" }}>
            Cancel
          </button>
          <button
            onClick={() => mutation.mutate()}
            disabled={!name || !raceDate || (!!maxDate && raceDate > maxDate) || mutation.isPending}
            style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", cursor: "pointer" }}
          >
            {mutation.isPending ? "Adding…" : "Add race"}
          </button>
        </div>
      </div>
    </div>
  );
}
