import { useCallback, useMemo } from "react";
import type { WorkoutSport } from "../../api/types";
import { flattenLeafSteps, leafDuration, targetInfo, type Leaf, type Step } from "./workoutTree";

const AXIS_MAX = 150; // % FTP/threshold headroom above 100%

interface BarGeometry {
  leaf: Leaf;
  key: string;
  x: number;
  width: number;
  yStart: number;
  yEnd: number;
  color: string;
}

export function WorkoutChart({
  steps,
  sport,
  thresholdPaceSecPerKm,
  selectedId,
  onSelect,
  compact,
}: {
  steps: Step[];
  sport: WorkoutSport;
  thresholdPaceSecPerKm: number | null;
  selectedId: string | null;
  onSelect: (id: string) => void;
  compact: boolean;
}) {
  const leaves = useMemo(() => flattenLeafSteps(steps), [steps]);
  // Falls back to a flat 60s placeholder width when no real or inferred duration is available
  // (e.g. a manual/open step, or a distance step this editor can't estimate yet) so it never
  // renders a zero-width bar.
  const barDuration = useCallback(
    (leaf: Leaf) => leafDuration(leaf, sport, thresholdPaceSecPerKm) || 60,
    [sport, thresholdPaceSecPerKm],
  );
  const totalDur = useMemo(() => leaves.reduce((a, c) => a + barDuration(c), 0) || 1, [leaves, barDuration]);
  const height = compact ? 110 : 220;

  // Each leaf becomes a trapezoid: target_low/target_high are the block's *start*/*end*
  // intensity (not a min/max bound - a cooldown is target_low=55 -> target_high=40, ramping
  // down), so the left edge always comes from target_low and the right edge from target_high,
  // whichever is numerically bigger. A flat target (low === high) degenerates to a rectangle.
  const bars = useMemo(
    () =>
      leaves.reduce<{ list: BarGeometry[]; cursor: number }>(
        (acc, leaf, i) => {
          const isOpen = leaf.target_type === "open";
          const startPct = isOpen ? 40 : (leaf.target_low ?? 60);
          const endPct = isOpen ? 40 : (leaf.target_high ?? startPct);
          const width = (barDuration(leaf) / totalDur) * 100;
          const bar: BarGeometry = {
            leaf,
            key: `${leaf.id}-${i}`, // a repeat group's leaves recur once per `repeat` when flattened, so leaf.id alone isn't unique
            x: acc.cursor,
            width,
            yStart: 100 - Math.min(100, (startPct / AXIS_MAX) * 100),
            yEnd: 100 - Math.min(100, (endPct / AXIS_MAX) * 100),
            color: targetInfo(leaf).color,
          };
          return { list: [...acc.list, bar], cursor: acc.cursor + width };
        },
        { list: [], cursor: 0 },
      ).list,
    [leaves, totalDur, barDuration],
  );

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
      <div style={{ flex: 1, minWidth: 0, height, background: "var(--elev)", borderRadius: 9, overflow: "hidden" }}>
        {/* viewBox is a fixed 0-100 square regardless of the container's actual (very wide,
            short) aspect ratio, so every leaf's slope is computed in simple 0-100 percent
            terms - preserveAspectRatio="none" does the non-uniform stretch to fit. */}
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" style={{ width: "100%", height: "100%", display: "block" }}>
          {bars.map(({ leaf, key, x, width, yStart, yEnd, color }) => (
            <g key={key} onClick={() => onSelect(leaf.id)} style={{ cursor: "pointer" }}>
              {/* Full-height hit area so clicking anywhere in the leaf's column selects it,
                  not just where the (possibly short) filled shape is. */}
              <rect x={x} y={0} width={width} height={100} fill="transparent" />
              <polygon
                points={`${x},${yStart} ${x + width},${yEnd} ${x + width},100 ${x},100`}
                fill={color}
                fillOpacity={leaf.id === selectedId ? 0.75 : 0.4}
                stroke={color}
                strokeWidth={2}
                vectorEffect="non-scaling-stroke"
              />
            </g>
          ))}
        </svg>
      </div>
    </div>
  );
}
