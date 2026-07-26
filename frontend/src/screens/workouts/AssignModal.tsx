import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { getContexts } from "../../api/auth";
import { scheduleWorkout } from "../../api/scheduling";
import { ApiError } from "../../api/types";

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

export function AssignModal({
  workoutId,
  workoutName,
  selfId,
  isCoach,
  onClose,
}: {
  workoutId: string;
  workoutName: string;
  selfId: string;
  isCoach: boolean;
  onClose: () => void;
}) {
  const { data: contexts } = useQuery({ queryKey: ["contexts"], queryFn: getContexts, enabled: isCoach });
  const [selected, setSelected] = useState<Record<string, boolean>>({ [selfId]: true });
  const [date, setDate] = useState(todayIso());

  const athletes = [{ id: selfId, name: "Me" }, ...(contexts?.coaching ?? []).map((a) => ({ id: a.user_id, name: a.name }))];

  const assignMutation = useMutation({
    mutationFn: async () => {
      const ids = Object.entries(selected)
        .filter(([, v]) => v)
        .map(([id]) => id);
      await Promise.all(ids.map((athleteId) => scheduleWorkout({ workout_id: workoutId, athlete_id: athleteId, date })));
    },
    onSuccess: onClose,
  });
  const assignError = assignMutation.isError
    ? assignMutation.error instanceof ApiError
      ? assignMutation.error.message
      : "Couldn't assign the workout. Try again."
    : null;

  const assignedCount = Object.values(selected).filter(Boolean).length;

  return (
    <div onClick={onClose} style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100, padding: 32 }}>
      <div onClick={(e) => e.stopPropagation()} style={{ width: 420, maxWidth: "100%", background: "var(--card)", borderRadius: 18, display: "flex", flexDirection: "column", overflow: "hidden" }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "18px 22px", borderBottom: "1px solid var(--line)" }}>
          <div style={{ fontSize: 16, fontWeight: 800, color: "var(--ink)" }}>Assign “{workoutName}”</div>
          <button onClick={onClose} style={{ width: 30, height: 30, borderRadius: 8, border: "1px solid var(--line)", background: "none", color: "var(--ink3)", cursor: "pointer", fontSize: 18 }}>
            ×
          </button>
        </div>
        <div style={{ padding: "18px 22px", display: "flex", flexDirection: "column", gap: 14 }}>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={{ fontSize: 11, fontWeight: 600, color: "var(--ink3)" }}>ASSIGN TO</label>
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              {athletes.map((a) => (
                <div
                  key={a.id}
                  onClick={() => setSelected((s) => ({ ...s, [a.id]: !s[a.id] }))}
                  style={{
                    padding: "7px 13px",
                    borderRadius: 20,
                    border: `1px solid ${selected[a.id] ? "var(--ember)" : "var(--line)"}`,
                    background: selected[a.id] ? "rgba(236,74,38,0.08)" : "transparent",
                    color: selected[a.id] ? "var(--ember)" : "var(--ink2)",
                    fontSize: 12.5,
                    fontWeight: 600,
                    cursor: "pointer",
                  }}
                >
                  {a.name}
                </div>
              ))}
            </div>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={{ fontSize: 11, fontWeight: 600, color: "var(--ink3)" }}>DATE</label>
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              style={{ padding: "9px 11px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", fontSize: 13, fontFamily: "monospace", fontWeight: 600, color: "var(--ink)" }}
            />
          </div>
          {assignError && <div style={{ fontSize: 13, color: "#e0442e" }}>{assignError}</div>}
        </div>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "flex-end", gap: 9, padding: "14px 22px", borderTop: "1px solid var(--line)", background: "var(--elev)" }}>
          <button onClick={onClose} style={{ padding: "9px 16px", borderRadius: 9, border: "1px solid var(--line)", background: "var(--card)", fontSize: 13, fontWeight: 600, color: "var(--ink2)", cursor: "pointer" }}>
            Cancel
          </button>
          <button
            onClick={() => assignMutation.mutate()}
            disabled={assignedCount === 0 || !date || assignMutation.isPending}
            style={{ padding: "9px 18px", borderRadius: 9, border: "none", background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, cursor: "pointer" }}
          >
            {assignedCount ? `Assign to ${assignedCount}` : "Assign"}
          </button>
        </div>
      </div>
    </div>
  );
}
