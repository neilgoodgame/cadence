import type { WorkoutSport } from "../../api/types";
import { templatesFor, type Step } from "./workoutTree";

export function TemplatesPanel({ sport, onInsert, onClose }: { sport: WorkoutSport; onInsert: (steps: Step[]) => void; onClose: () => void }) {
  const templates = templatesFor(sport);
  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ background: "var(--card)", borderRadius: 14, width: 520, maxWidth: "100%", maxHeight: "80vh", display: "flex", flexDirection: "column", overflow: "hidden" }}
      >
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "18px 22px", borderBottom: "1px solid var(--line)" }}>
          <div style={{ fontSize: 16, fontWeight: 800, color: "var(--ink)" }}>Insert template — {sport.toUpperCase()}</div>
          <button onClick={onClose} style={{ width: 30, height: 30, borderRadius: 8, border: "1px solid var(--line)", background: "none", color: "var(--ink3)", cursor: "pointer", fontSize: 18 }}>
            ×
          </button>
        </div>
        <div style={{ padding: 14, display: "flex", flexDirection: "column", gap: 8, overflow: "auto" }}>
          {templates.map((tpl) => (
            <div
              key={tpl.name}
              onClick={() => {
                onInsert(tpl.build());
                onClose();
              }}
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: 12,
                padding: "13px 15px",
                borderRadius: 10,
                border: "1px solid var(--line)",
                cursor: "pointer",
              }}
            >
              <div>
                <div style={{ fontSize: 13.5, fontWeight: 700, color: "var(--ink)" }}>{tpl.name}</div>
                <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 1 }}>{tpl.desc}</div>
              </div>
              <div style={{ fontFamily: "monospace", fontSize: 12, color: "var(--ember)", fontWeight: 600, flexShrink: 0 }}>+ Add</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
