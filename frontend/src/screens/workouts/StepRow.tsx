import type { StepEndType, StepKind, WorkoutStep } from "../../api/types";

const KINDS: StepKind[] = ["warmup", "block", "rec", "cool"];
const END_TYPES: StepEndType[] = ["time", "distance", "manual"];

const inputStyle: React.CSSProperties = {
  padding: "6px 8px",
  borderRadius: 6,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 13,
  color: "var(--ink)",
};

export function StepRow({
  step,
  onChange,
  onRemove,
  onMoveUp,
  onMoveDown,
}: {
  step: WorkoutStep;
  onChange: (step: WorkoutStep) => void;
  onRemove: () => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
}) {
  return (
    <div style={{ display: "flex", gap: 6, alignItems: "center", padding: "6px 0", borderTop: "1px solid var(--line)" }}>
      <select value={step.kind} onChange={(e) => onChange({ ...step, kind: e.target.value as StepKind })} style={inputStyle}>
        {KINDS.map((k) => (
          <option key={k} value={k}>
            {k}
          </option>
        ))}
      </select>
      <select
        value={step.end_type}
        onChange={(e) => onChange({ ...step, end_type: e.target.value as StepEndType })}
        style={inputStyle}
      >
        {END_TYPES.map((t) => (
          <option key={t} value={t}>
            {t}
          </option>
        ))}
      </select>
      {step.end_type === "time" && (
        <input
          type="number"
          placeholder="Seconds"
          value={step.duration ?? ""}
          onChange={(e) => onChange({ ...step, duration: e.target.value ? Number(e.target.value) : null })}
          style={{ ...inputStyle, width: 90 }}
        />
      )}
      {step.end_type === "distance" && (
        <input
          type="number"
          placeholder="Metres"
          value={step.distance ?? ""}
          onChange={(e) => onChange({ ...step, distance: e.target.value ? Number(e.target.value) : null })}
          style={{ ...inputStyle, width: 90 }}
        />
      )}
      <input
        type="number"
        placeholder="% FTP"
        value={step.target_pct}
        onChange={(e) => onChange({ ...step, target_pct: Number(e.target.value) })}
        style={{ ...inputStyle, width: 80 }}
      />
      <input
        type="number"
        placeholder="x repeat"
        value={step.repeat}
        onChange={(e) => onChange({ ...step, repeat: Number(e.target.value) || 1 })}
        style={{ ...inputStyle, width: 80 }}
      />
      <button onClick={onMoveUp} style={{ border: "none", background: "none", color: "var(--ink3)", padding: "0 4px" }}>
        ↑
      </button>
      <button onClick={onMoveDown} style={{ border: "none", background: "none", color: "var(--ink3)", padding: "0 4px" }}>
        ↓
      </button>
      <button onClick={onRemove} style={{ border: "none", background: "none", color: "#e0442e", padding: "0 4px" }}>
        Remove
      </button>
    </div>
  );
}
