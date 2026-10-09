import { useState } from "react";
import { BikesSection } from "./gear/BikesSection";
import { ShoesSection } from "./gear/ShoesSection";
import { type GearView, loadGearView, saveGearView } from "./gear/viewMode";

const VIEWS: { value: GearView; label: string }[] = [
  { value: "cards", label: "Cards" },
  { value: "table", label: "Table" },
];

export function GearScreen() {
  const [view, setView] = useState<GearView>(loadGearView);

  const choose = (next: GearView) => {
    setView(next);
    saveGearView(next);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 36 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>Gear</h1>
        <div role="group" aria-label="View" style={{ display: "flex", border: "1px solid var(--line)", borderRadius: 8, overflow: "hidden" }}>
          {VIEWS.map((v) => (
            <button
              key={v.value}
              onClick={() => choose(v.value)}
              aria-pressed={view === v.value}
              style={{
                border: "none",
                padding: "6px 12px",
                fontSize: 13,
                fontWeight: 600,
                cursor: "pointer",
                background: view === v.value ? "var(--ember)" : "var(--card)",
                color: view === v.value ? "#fff" : "var(--ink2)",
              }}
            >
              {v.label}
            </button>
          ))}
        </div>
      </div>
      <BikesSection view={view} />
      <ShoesSection view={view} />
    </div>
  );
}
