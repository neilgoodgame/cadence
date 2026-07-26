import { useMemo } from "react";
import { flattenLeafSteps, targetInfo, type Step } from "./workoutTree";

const AXIS_MAX = 150; // % FTP/threshold headroom above 100%

export function WorkoutChart({
  steps,
  selectedId,
  onSelect,
  compact,
}: {
  steps: Step[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  compact: boolean;
}) {
  const leaves = useMemo(() => flattenLeafSteps(steps), [steps]);
  const totalDur = useMemo(() => leaves.reduce((a, c) => a + (c.duration || 60), 0) || 1, [leaves]);
  const height = compact ? 110 : 220;

  return (
    <div style={{ display: "flex", gap: 8 }}>
      {!compact && (
        <div style={{ width: 34, flexShrink: 0, position: "relative", height, fontFamily: "monospace", fontSize: 9, color: "var(--ink3)" }}>
          <span style={{ position: "absolute", top: 0 }}>150%</span>
          <span style={{ position: "absolute", top: "33%" }}>100%</span>
          <span style={{ position: "absolute", top: "66%" }}>50%</span>
          <span style={{ position: "absolute", bottom: 0 }}>0%</span>
        </div>
      )}
      <div style={{ flex: 1, minWidth: 0, display: "flex", alignItems: "flex-end", height, background: "var(--elev)", borderRadius: 9, overflow: "hidden", position: "relative" }}>
        {leaves.map((leaf, i) => {
          const t = targetInfo(leaf);
          const avg = leaf.target_type === "open" ? 40 : ((leaf.target_low ?? 60) + (leaf.target_high ?? leaf.target_low ?? 60)) / 2;
          const hPct = Math.min(100, (avg / AXIS_MAX) * 100);
          const width = ((leaf.duration || 60) / totalDur) * 100;
          return (
            <div
              // A repeat group's leaves recur multiple times when unrolled (flattenLeafSteps
              // concats the same steps once per `repeat`), so `leaf.id` alone isn't a unique
              // key here - only the flattened position is.
              key={`${leaf.id}-${i}`}
              onClick={() => onSelect(leaf.id)}
              style={{ position: "relative", width: `${width}%`, height: "100%", display: "flex", alignItems: "flex-end", borderRight: "1px solid var(--card)", cursor: "pointer" }}
            >
              <div style={{ width: "100%", height: `${hPct}%`, background: t.color, opacity: leaf.id === selectedId ? 0.75 : 0.4, borderTop: `2px solid ${t.color}` }} />
            </div>
          );
        })}
      </div>
    </div>
  );
}
