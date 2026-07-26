import type { CSSProperties } from "react";
import { fmtDuration, isGroup, kindLabel, stepDetail, targetInfo, totalDuration, type Group, type Leaf, type Step } from "./workoutTree";

export interface StructureActions {
  selectedId: string | null;
  onSelect: (id: string) => void;
  onMoveUp: (id: string) => void;
  onMoveDown: (id: string) => void;
  onDuplicate: (id: string) => void;
  onRemove: (id: string) => void;
  onRepeatChange: (id: string, repeat: number) => void;
  onAddChild: (groupId: string) => void;
  onAddNestedGroup: (groupId: string) => void;
}

const iconBtnStyle: CSSProperties = {
  width: 22,
  height: 22,
  borderRadius: 6,
  border: "none",
  background: "transparent",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  color: "var(--ink3)",
  cursor: "pointer",
  fontSize: 12,
};

function RowControls({ id, actions }: { id: string; actions: StructureActions }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 2, flexShrink: 0 }}>
      <button style={iconBtnStyle} onClick={(e) => (e.stopPropagation(), actions.onMoveUp(id))} title="Move up">
        ↑
      </button>
      <button style={iconBtnStyle} onClick={(e) => (e.stopPropagation(), actions.onMoveDown(id))} title="Move down">
        ↓
      </button>
      <button style={iconBtnStyle} onClick={(e) => (e.stopPropagation(), actions.onDuplicate(id))} title="Duplicate">
        ⧉
      </button>
      <button style={iconBtnStyle} onClick={(e) => (e.stopPropagation(), actions.onRemove(id))} title="Delete">
        ✕
      </button>
    </div>
  );
}

function LeafRow({ step, actions }: { step: Leaf; actions: StructureActions }) {
  const selected = step.id === actions.selectedId;
  const t = targetInfo(step);
  return (
    <div
      onClick={() => actions.onSelect(step.id)}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 12,
        padding: "10px 12px",
        borderRadius: 10,
        cursor: "pointer",
        background: selected ? "var(--emberSoft, rgba(236,74,38,0.08))" : "var(--elev)",
        border: `1px solid ${selected ? "var(--ember)" : "var(--line)"}`,
      }}
    >
      <div style={{ width: 5, alignSelf: "stretch", borderRadius: 3, background: t.color, flexShrink: 0 }} />
      <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 2 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
          <span style={{ fontSize: 13, fontWeight: 700, color: "var(--ink)" }}>{kindLabel(step.kind)}</span>
          {step.note && <span style={{ fontSize: 11, color: "var(--ink3)" }}>✎ {step.note}</span>}
        </div>
        <div style={{ fontFamily: "monospace", fontSize: 12, color: "var(--ink2)" }}>{stepDetail(step)}</div>
      </div>
      <RowControls id={step.id} actions={actions} />
    </div>
  );
}

function GroupRow({ step, actions, depth }: { step: Group; actions: StructureActions; depth: number }) {
  const selected = step.id === actions.selectedId;
  return (
    <div>
      <div
        onClick={() => actions.onSelect(step.id)}
        style={{
          display: "flex",
          alignItems: "center",
          gap: 12,
          padding: "10px 12px",
          borderRadius: 10,
          cursor: "pointer",
          background: selected ? "var(--emberSoft, rgba(236,74,38,0.08))" : "rgba(236,74,38,0.05)",
          border: `1px solid ${selected ? "var(--ember)" : "rgba(236,74,38,0.25)"}`,
        }}
      >
        <div style={{ width: 5, alignSelf: "stretch", borderRadius: 3, background: "var(--ember)", flexShrink: 0 }} />
        <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 2 }}>
          <span style={{ fontSize: 13, fontWeight: 700, color: "var(--ink)" }}>Repeat group</span>
          <div style={{ fontFamily: "monospace", fontSize: 12, color: "var(--ink2)" }}>
            {step.children.length} steps × {step.repeat} · {fmtDuration(totalDuration(step.children) * step.repeat)} total
          </div>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 4, background: "var(--canvas, transparent)", borderRadius: 7, padding: 3 }}>
          <button
            style={{ ...iconBtnStyle, fontWeight: 700 }}
            onClick={(e) => (e.stopPropagation(), actions.onRepeatChange(step.id, Math.max(1, step.repeat - 1)))}
          >
            –
          </button>
          <span style={{ fontFamily: "monospace", fontSize: 12, fontWeight: 700, minWidth: 16, textAlign: "center" }}>{step.repeat}</span>
          <button style={{ ...iconBtnStyle, fontWeight: 700 }} onClick={(e) => (e.stopPropagation(), actions.onRepeatChange(step.id, step.repeat + 1))}>
            +
          </button>
        </div>
        <RowControls id={step.id} actions={actions} />
      </div>
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          gap: 6,
          margin: "6px 0 2px 22px",
          padding: "0 0 0 12px",
          borderLeft: `2px solid ${depth === 0 ? "var(--line)" : "rgba(236,74,38,0.25)"}`,
        }}
      >
        <WorkoutStructureList steps={step.children} actions={actions} depth={depth + 1} />
        <div style={{ display: "flex", gap: 8 }}>
          <button
            onClick={() => actions.onAddChild(step.id)}
            style={{ alignSelf: "flex-start", padding: "5px 11px", borderRadius: 7, border: "1px dashed var(--line)", background: "none", fontSize: 12, fontWeight: 600, color: "var(--ink3)", cursor: "pointer" }}
          >
            + step in group
          </button>
          <button
            onClick={() => actions.onAddNestedGroup(step.id)}
            style={{ alignSelf: "flex-start", padding: "5px 11px", borderRadius: 7, border: "1px dashed var(--ember)", background: "none", fontSize: 12, fontWeight: 600, color: "var(--ember)", cursor: "pointer" }}
          >
            + nested repeat group
          </button>
        </div>
      </div>
    </div>
  );
}

export function WorkoutStructureList({ steps, actions, depth = 0 }: { steps: Step[]; actions: StructureActions; depth?: number }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {steps.map((step) =>
        isGroup(step) ? (
          <GroupRow key={step.id} step={step} actions={actions} depth={depth} />
        ) : (
          <LeafRow key={step.id} step={step} actions={actions} />
        ),
      )}
    </div>
  );
}
