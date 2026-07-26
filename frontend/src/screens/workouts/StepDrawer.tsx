import type { CSSProperties } from "react";
import type { StepEndType, StepKind, Target2Type, TargetType } from "../../api/types";
import { fmtDuration, isGroup, parseDuration, targetInfo, type Group, type Leaf, type Step } from "./workoutTree";

const KINDS: StepKind[] = ["warmup", "block", "rec", "cool"];
const END_TYPES: StepEndType[] = ["time", "distance", "manual"];
const TARGET_TYPES: TargetType[] = ["power", "hr", "pace", "cadence", "open"];

const fieldStyle: CSSProperties = {
  padding: "8px 10px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 13,
  fontWeight: 600,
  color: "var(--ink)",
  width: "100%",
};
const labelStyle: CSSProperties = { fontSize: 11, fontWeight: 600, color: "var(--ink3)" };
const rampFromLabel: Record<TargetType, string> = {
  power: "FROM % FTP",
  hr: "FROM % MAX HR",
  pace: "FROM % PACE",
  cadence: "RPM",
  open: "FROM",
};

export function StepDrawer({
  step,
  onChange,
  onRemove,
  onClose,
}: {
  step: Step | null;
  onChange: (step: Step) => void;
  onRemove: (id: string) => void;
  onClose: () => void;
}) {
  if (!step) {
    return (
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 10, padding: "30px 6px" }}>
        <div style={{ fontSize: 13, color: "var(--ink3)", textAlign: "center", maxWidth: 220 }}>
          Select a step in the structure to edit its target, duration and notes.
        </div>
      </div>
    );
  }

  return isGroup(step) ? (
    <GroupDrawer step={step} onChange={onChange} onRemove={onRemove} onClose={onClose} />
  ) : (
    <LeafDrawer step={step} onChange={onChange} onRemove={onRemove} onClose={onClose} />
  );
}

function DrawerHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
      <div style={{ fontSize: 15, fontWeight: 700, color: "var(--ink)" }}>{title}</div>
      <button
        onClick={onClose}
        style={{ width: 26, height: 26, borderRadius: 7, border: "1px solid var(--line)", background: "none", color: "var(--ink3)", cursor: "pointer" }}
      >
        ×
      </button>
    </div>
  );
}

function DeleteButton({ id, onRemove }: { id: string; onRemove: (id: string) => void }) {
  return (
    <button
      onClick={() => onRemove(id)}
      style={{
        textAlign: "center",
        padding: 9,
        borderRadius: 8,
        border: "1px solid rgba(196,51,42,0.3)",
        background: "none",
        color: "#c4332a",
        fontSize: 13,
        fontWeight: 600,
        cursor: "pointer",
      }}
    >
      Delete step
    </button>
  );
}

function GroupDrawer({ step, onChange, onRemove, onClose }: { step: Group; onChange: (step: Step) => void; onRemove: (id: string) => void; onClose: () => void }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <DrawerHeader title="Repeat group" onClose={onClose} />
      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <label style={labelStyle}>REPEAT COUNT</label>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <button
            onClick={() => onChange({ ...step, repeat: Math.max(1, step.repeat - 1) })}
            style={{ width: 32, height: 32, borderRadius: 8, border: "1px solid var(--line)", background: "none", fontWeight: 700, cursor: "pointer" }}
          >
            –
          </button>
          <span style={{ fontFamily: "monospace", fontSize: 16, fontWeight: 700, minWidth: 24, textAlign: "center" }}>{step.repeat}</span>
          <button
            onClick={() => onChange({ ...step, repeat: step.repeat + 1 })}
            style={{ width: 32, height: 32, borderRadius: 8, border: "1px solid var(--line)", background: "none", fontWeight: 700, cursor: "pointer" }}
          >
            +
          </button>
        </div>
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <label style={labelStyle}>COACH NOTE</label>
        <textarea
          value={step.note}
          onChange={(e) => onChange({ ...step, note: e.target.value })}
          placeholder="Cue shown on-device during this step…"
          style={{ ...fieldStyle, minHeight: 56, resize: "vertical", fontFamily: "inherit" }}
        />
      </div>
      <DeleteButton id={step.id} onRemove={onRemove} />
    </div>
  );
}

function LeafDrawer({ step, onChange, onRemove, onClose }: { step: Leaf; onChange: (step: Step) => void; onRemove: (id: string) => void; onClose: () => void }) {
  const hasRange = step.target_type !== "open";
  const isRamp = step.target_low !== step.target_high;
  const preview = targetInfo(step);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <DrawerHeader title={step.kind[0].toUpperCase() + step.kind.slice(1)} onClose={onClose} />

      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <label style={labelStyle}>STEP TYPE</label>
        <select value={step.kind} onChange={(e) => onChange({ ...step, kind: e.target.value as StepKind })} style={fieldStyle}>
          {KINDS.map((k) => (
            <option key={k} value={k}>
              {k}
            </option>
          ))}
        </select>
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <label style={labelStyle}>ENDS BY</label>
        <select value={step.end_type} onChange={(e) => onChange({ ...step, end_type: e.target.value as StepEndType })} style={fieldStyle}>
          {END_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </div>

      {step.end_type === "time" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          <label style={labelStyle}>DURATION (HH:MM:SS)</label>
          <input
            value={fmtDuration(step.duration || 0)}
            onChange={(e) => onChange({ ...step, duration: parseDuration(e.target.value) })}
            style={{ ...fieldStyle, fontFamily: "monospace" }}
          />
        </div>
      )}
      {step.end_type === "distance" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          <label style={labelStyle}>DISTANCE (M)</label>
          <input
            type="number"
            value={step.distance ?? ""}
            onChange={(e) => onChange({ ...step, distance: e.target.value ? Number(e.target.value) : null })}
            style={{ ...fieldStyle, fontFamily: "monospace" }}
          />
        </div>
      )}

      <div style={{ height: 1, background: "var(--line)" }} />

      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <label style={labelStyle}>PRIMARY TARGET</label>
        <select value={step.target_type} onChange={(e) => onChange({ ...step, target_type: e.target.value as TargetType })} style={fieldStyle}>
          {TARGET_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </div>

      {hasRange && (
        <>
          <label style={{ fontSize: 11.5, color: "var(--ink2)", display: "flex", alignItems: "center", gap: 6, cursor: "pointer" }}>
            <input
              type="checkbox"
              checked={isRamp}
              onChange={(e) => onChange({ ...step, target_high: e.target.checked ? (step.target_high === step.target_low ? (step.target_low ?? 0) + 15 : step.target_high) : step.target_low })}
            />
            Ramp (different start/end)
          </label>
          <div style={{ display: "flex", gap: 10 }}>
            <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: 6 }}>
              <label style={labelStyle}>{rampFromLabel[step.target_type]}</label>
              <input
                type="number"
                value={step.target_low ?? ""}
                onChange={(e) => {
                  const v = e.target.value ? Number(e.target.value) : 0;
                  onChange({ ...step, target_low: v, target_high: step.target_low === step.target_high ? v : step.target_high });
                }}
                style={{ ...fieldStyle, fontFamily: "monospace" }}
              />
            </div>
            {isRamp && (
              <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: 6 }}>
                <label style={labelStyle}>TO</label>
                <input
                  type="number"
                  value={step.target_high ?? ""}
                  onChange={(e) => onChange({ ...step, target_high: e.target.value ? Number(e.target.value) : 0 })}
                  style={{ ...fieldStyle, fontFamily: "monospace" }}
                />
              </div>
            )}
          </div>
          <div style={{ fontSize: 11.5, color: "var(--ink3)" }}>{preview.primary}</div>
        </>
      )}

      <div style={{ height: 1, background: "var(--line)" }} />

      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <label style={labelStyle}>SECONDARY TARGET</label>
        <select value={step.target2_type} onChange={(e) => onChange({ ...step, target2_type: e.target.value as Target2Type })} style={fieldStyle}>
          <option value="none">None</option>
          <option value="cadence">Cadence</option>
        </select>
      </div>
      {step.target2_type === "cadence" && (
        <div style={{ display: "flex", gap: 10 }}>
          <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={labelStyle}>RPM LOW</label>
            <input
              type="number"
              value={step.target2_low ?? ""}
              onChange={(e) => onChange({ ...step, target2_low: e.target.value ? Number(e.target.value) : 0 })}
              style={{ ...fieldStyle, fontFamily: "monospace" }}
            />
          </div>
          <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={labelStyle}>RPM HIGH</label>
            <input
              type="number"
              value={step.target2_high ?? ""}
              onChange={(e) => onChange({ ...step, target2_high: e.target.value ? Number(e.target.value) : 0 })}
              style={{ ...fieldStyle, fontFamily: "monospace" }}
            />
          </div>
        </div>
      )}

      <div style={{ height: 1, background: "var(--line)" }} />

      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <label style={labelStyle}>COACH NOTE</label>
        <textarea
          value={step.note}
          onChange={(e) => onChange({ ...step, note: e.target.value })}
          placeholder="Cue shown on-device during this step…"
          style={{ ...fieldStyle, minHeight: 56, resize: "vertical", fontFamily: "inherit" }}
        />
      </div>

      <DeleteButton id={step.id} onRemove={onRemove} />
    </div>
  );
}
