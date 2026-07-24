import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createRace } from "../../api/races";

const SPORTS = ["run", "bike", "swim", "multisport"];

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

export function AddRaceModal({ date, onClose }: { date: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [raceDate, setRaceDate] = useState(date);
  const [sport, setSport] = useState("");
  const [distanceKm, setDistanceKm] = useState("");
  const [goalTime, setGoalTime] = useState("");
  const [notes, setNotes] = useState("");

  const mutation = useMutation({
    mutationFn: () =>
      createRace({
        name,
        date: raceDate,
        sport: sport || undefined,
        distance_km: distanceKm ? parseFloat(distanceKm) : null,
        goal_time: goalTime || null,
        notes,
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
          <input type="date" value={raceDate} onChange={(e) => setRaceDate(e.target.value)} style={fieldStyle} />
        </label>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
          <label>
            <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Sport (optional)</div>
            <select value={sport} onChange={(e) => setSport(e.target.value)} style={fieldStyle}>
              <option value="">—</option>
              {SPORTS.map((s) => <option key={s} value={s}>{s.charAt(0).toUpperCase() + s.slice(1)}</option>)}
            </select>
          </label>
          <label>
            <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Distance km (optional)</div>
            <input type="number" step="0.1" value={distanceKm} onChange={(e) => setDistanceKm(e.target.value)} placeholder="42.2" style={fieldStyle} />
          </label>
        </div>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Goal time (optional)</div>
          <input value={goalTime} onChange={(e) => setGoalTime(e.target.value)} placeholder="H:MM:SS" style={fieldStyle} />
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
            disabled={!name || !raceDate || mutation.isPending}
            style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", cursor: "pointer" }}
          >
            {mutation.isPending ? "Adding…" : "Add race"}
          </button>
        </div>
      </div>
    </div>
  );
}
