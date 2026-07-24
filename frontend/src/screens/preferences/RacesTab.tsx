import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createRace, deleteRace, listRaces, updateRace } from "../../api/races";
import type { Race } from "../../api/types";

const SPORTS = ["run", "bike", "swim", "multisport"];

function formatGoalTime(iso: string | null): string {
  if (!iso) return "";
  // Django DurationField returns "HH:MM:SS" or "H:MM:SS"
  return iso;
}

function RaceRow({ race, onDelete }: { race: Race; onDelete: () => void }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(race.name);
  const [date, setDate] = useState(race.date);
  const [sport, setSport] = useState(race.sport);
  const [distanceKm, setDistanceKm] = useState(race.distance_km?.toString() ?? "");
  const [goalTime, setGoalTime] = useState(formatGoalTime(race.goal_time));
  const [resultTime, setResultTime] = useState(formatGoalTime(race.result_time));
  const [notes, setNotes] = useState(race.notes);

  const saveMutation = useMutation({
    mutationFn: () =>
      updateRace(race.id, {
        name,
        date,
        sport: sport || undefined,
        distance_km: distanceKm ? parseFloat(distanceKm) : null,
        goal_time: goalTime || null,
        result_time: resultTime || null,
        notes,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["races"] });
      setEditing(false);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteRace(race.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["races"] });
      onDelete();
    },
  });

  const isPast = race.date < new Date().toISOString().slice(0, 10);

  if (editing) {
    return (
      <div style={{ padding: "12px 0", borderTop: "1px solid var(--line)" }}>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, marginBottom: 8 }}>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Race name" style={inputStyle} />
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={inputStyle} />
          <select value={sport} onChange={(e) => setSport(e.target.value)} style={inputStyle}>
            <option value="">Sport (optional)</option>
            {SPORTS.map((s) => <option key={s} value={s}>{s.charAt(0).toUpperCase() + s.slice(1)}</option>)}
          </select>
          <input value={distanceKm} onChange={(e) => setDistanceKm(e.target.value)} placeholder="Distance (km)" type="number" step="0.1" style={inputStyle} />
          <input value={goalTime} onChange={(e) => setGoalTime(e.target.value)} placeholder="Goal time (H:MM:SS)" style={inputStyle} />
          <input value={resultTime} onChange={(e) => setResultTime(e.target.value)} placeholder="Result time (H:MM:SS)" style={inputStyle} />
        </div>
        <input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Notes" style={{ ...inputStyle, width: "100%", boxSizing: "border-box" }} />
        <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
          <button onClick={() => saveMutation.mutate()} disabled={!name || !date || saveMutation.isPending} style={primaryBtn}>
            {saveMutation.isPending ? "Saving…" : "Save"}
          </button>
          <button onClick={() => setEditing(false)} style={ghostBtn}>Cancel</button>
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "10px 0", borderTop: "1px solid var(--line)", opacity: isPast && !race.result_time ? 0.6 : 1 }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontWeight: 600, fontSize: 14 }}>{race.name}</div>
        <div style={{ fontSize: 12, color: "var(--ink3)" }}>
          {race.date}
          {race.sport && ` · ${race.sport.charAt(0).toUpperCase() + race.sport.slice(1)}`}
          {race.distance_km != null && ` · ${race.distance_km} km`}
          {race.goal_time && ` · Goal: ${race.goal_time}`}
          {race.result_time && ` · Result: ${race.result_time}`}
        </div>
        {race.notes && <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>{race.notes}</div>}
      </div>
      {isPast && !race.result_time && (
        <span style={{ fontSize: 11, color: "var(--ink3)", fontStyle: "italic" }}>no result</span>
      )}
      {race.activity_id && (
        <span style={{ fontSize: 11, color: "#2fa66a", fontWeight: 600 }}>linked</span>
      )}
      <button onClick={() => setEditing(true)} style={ghostBtn}>Edit</button>
      <button onClick={() => deleteMutation.mutate()} disabled={deleteMutation.isPending} style={{ ...ghostBtn, color: "#e0442e" }}>Delete</button>
    </div>
  );
}

export function RacesTab() {
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: ["races"], queryFn: listRaces });
  const [showForm, setShowForm] = useState(false);
  const [name, setName] = useState("");
  const [date, setDate] = useState("");
  const [sport, setSport] = useState("");
  const [distanceKm, setDistanceKm] = useState("");
  const [goalTime, setGoalTime] = useState("");
  const [notes, setNotes] = useState("");

  const createMutation = useMutation({
    mutationFn: () =>
      createRace({
        name,
        date,
        sport: sport || undefined,
        distance_km: distanceKm ? parseFloat(distanceKm) : null,
        goal_time: goalTime || null,
        notes,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["races"] });
      setShowForm(false);
      setName("");
      setDate("");
      setSport("");
      setDistanceKm("");
      setGoalTime("");
      setNotes("");
    },
  });

  const races = data?.data ?? [];
  const today = new Date().toISOString().slice(0, 10);
  const upcoming = races.filter((r) => r.date >= today);
  const past = races.filter((r) => r.date < today);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 24, maxWidth: 560 }}>
      <p style={{ fontSize: 13, color: "var(--ink3)", margin: 0 }}>
        Schedule upcoming races and log results. Link a completed race to an activity to track your result automatically.
      </p>

      {showForm && (
        <div style={{ background: "var(--elev)", borderRadius: 10, padding: 16 }}>
          <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Add race</h3>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, marginBottom: 8 }}>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Race name" style={inputStyle} />
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={inputStyle} />
            <select value={sport} onChange={(e) => setSport(e.target.value)} style={inputStyle}>
              <option value="">Sport (optional)</option>
              {SPORTS.map((s) => <option key={s} value={s}>{s.charAt(0).toUpperCase() + s.slice(1)}</option>)}
            </select>
            <input value={distanceKm} onChange={(e) => setDistanceKm(e.target.value)} placeholder="Distance (km)" type="number" step="0.1" style={inputStyle} />
            <input value={goalTime} onChange={(e) => setGoalTime(e.target.value)} placeholder="Goal time (H:MM:SS)" style={inputStyle} />
          </div>
          <input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Notes" style={{ ...inputStyle, width: "100%", boxSizing: "border-box", marginBottom: 8 }} />
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => createMutation.mutate()} disabled={!name || !date || createMutation.isPending} style={primaryBtn}>
              {createMutation.isPending ? "Adding…" : "Add race"}
            </button>
            <button onClick={() => setShowForm(false)} style={ghostBtn}>Cancel</button>
          </div>
        </div>
      )}

      {!showForm && (
        <button onClick={() => setShowForm(true)} style={primaryBtn}>+ Add race</button>
      )}

      {upcoming.length > 0 && (
        <div>
          <h3 style={{ fontSize: 13, fontWeight: 700, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.06em", margin: "0 0 4px" }}>Upcoming</h3>
          {upcoming.map((r) => <RaceRow key={r.id} race={r} onDelete={() => {}} />)}
        </div>
      )}

      {past.length > 0 && (
        <div>
          <h3 style={{ fontSize: 13, fontWeight: 700, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.06em", margin: "0 0 4px" }}>Past</h3>
          {past.map((r) => <RaceRow key={r.id} race={r} onDelete={() => {}} />)}
        </div>
      )}

      {races.length === 0 && !showForm && (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>No races yet.</div>
      )}
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  padding: "8px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  color: "var(--ink)",
  fontSize: 13,
  width: "100%",
  boxSizing: "border-box",
};

const primaryBtn: React.CSSProperties = {
  alignSelf: "flex-start",
  padding: "8px 16px",
  borderRadius: 8,
  border: "none",
  background: "var(--ember)",
  color: "#fff",
  fontSize: 13,
  fontWeight: 700,
  cursor: "pointer",
};

const ghostBtn: React.CSSProperties = {
  padding: "6px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "none",
  color: "var(--ink2)",
  fontSize: 13,
  fontWeight: 600,
  cursor: "pointer",
};
