import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateRace } from "../../api/races";
import { formatDuration, formatPace, parseDuration } from "../../lib/format";

const GOAL_TIME_PATTERN = /^\d{1,2}(:[0-5]\d){1,2}$/;

/** README §4.2.3 - opened from "Set goal"/"Edit" on the Goal/Result card. Blank input means
 * "no goal" (Save with an empty field behaves like Remove goal). */
export function InlineGoalEditor({
  raceId,
  distanceKm,
  currentGoal,
  pbS,
  predictionS,
  onClose,
}: {
  raceId: string;
  distanceKm: number | null;
  currentGoal: string | null;
  pbS: number | null;
  predictionS: number | null;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [value, setValue] = useState(currentGoal ?? "");
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (goalTime: string | null) => updateRace(raceId, { goal_time: goalTime }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["race", raceId] });
      queryClient.invalidateQueries({ queryKey: ["race-block", raceId] });
      onClose();
    },
  });

  function save() {
    const trimmed = value.trim();
    if (trimmed === "") {
      mutation.mutate(null);
      return;
    }
    if (!GOAL_TIME_PATTERN.test(trimmed)) {
      setError("Use H:MM:SS or MM:SS.");
      return;
    }
    setError(null);
    mutation.mutate(trimmed);
  }

  const parsed = GOAL_TIME_PATTERN.test(value.trim()) ? parseDuration(value.trim()) : null;
  const pace = parsed != null && distanceKm ? formatPace(parsed / distanceKm) : null;
  const vsPb = parsed != null && pbS != null ? parsed - pbS : null;

  const presets: { label: string; seconds: number }[] = [];
  if (pbS != null) {
    presets.push({ label: "PB", seconds: pbS });
    presets.push({ label: "PB −1%", seconds: pbS * 0.99 });
  }
  if (predictionS != null) presets.push({ label: "Predicted", seconds: predictionS });

  return (
    <div style={{ padding: "12px 14px", borderRadius: 10, background: "var(--canvas-solid)", border: "1px solid var(--line)" }}>
      <input
        autoFocus
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") save();
          if (e.key === "Escape") onClose();
        }}
        placeholder="H:MM:SS"
        className="mono"
        style={{ width: 140, fontSize: 18, padding: "6px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)" }}
      />
      {(pace || vsPb != null) && (
        <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 6 }}>
          {pace}
          {vsPb != null && ` · ${vsPb <= 0 ? "-" : "+"}${formatDuration(Math.abs(vsPb))} vs PB`}
        </div>
      )}
      {error && <div style={{ fontSize: 12, color: "#e0442e", marginTop: 6 }}>{error}</div>}
      {presets.length > 0 && (
        <div style={{ display: "flex", gap: 6, marginTop: 10, flexWrap: "wrap" }}>
          {presets.map((p) => (
            <button
              key={p.label}
              type="button"
              onClick={() => setValue(formatDuration(p.seconds))}
              style={{ fontSize: 11.5, padding: "3px 9px", borderRadius: 20, border: "1px solid var(--line)", background: "none", color: "var(--ink2)", cursor: "pointer" }}
            >
              {p.label} · {formatDuration(p.seconds)}
            </button>
          ))}
        </div>
      )}
      <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
        <button
          onClick={save}
          disabled={mutation.isPending}
          style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 12.5, fontWeight: 700, padding: "6px 14px", cursor: "pointer" }}
        >
          Save
        </button>
        <button onClick={onClose} style={{ border: "1px solid var(--line)", background: "none", borderRadius: 8, fontSize: 12.5, fontWeight: 600, padding: "6px 14px", cursor: "pointer", color: "var(--ink2)" }}>
          Cancel
        </button>
        {currentGoal && (
          <button
            onClick={() => mutation.mutate(null)}
            disabled={mutation.isPending}
            style={{ border: "none", background: "none", fontSize: 12.5, fontWeight: 600, color: "#e0442e", cursor: "pointer" }}
          >
            Remove goal
          </button>
        )}
      </div>
    </div>
  );
}
