import { useState } from "react";
import {
  BEST_EFFORT_FAMILY,
  BEST_EFFORT_FAMILY_ORDER,
  MEDAL_COLORS,
  MEDAL_INK,
  RECENT_TOP_EFFORTS_CAP,
  RECENT_TOP_EFFORT_PERIODS,
  formatBestEffortValue,
  windowLabel,
  type QualifyingRecentEffort,
} from "../lib/bestEfforts";

/** Shared by the Dashboard's "Top efforts this week" card (TopEffortsCard.tsx) and the
 * Activity Analysis screen's own best-efforts card (ActivityBestEffortsCard.tsx) - both render
 * the exact same "family label -> effort rows, each with three period chips" shape, one scoped
 * to many activities grouped by activity, the other to a single activity's own efforts. */

export function TrophyIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#c9981a" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 3h8v5a4 4 0 0 1-8 0V3Z" />
      <path d="M8 4H5a2 2 0 0 0 0 4h1.5M16 4h3a2 2 0 0 1 0 4h-1.5" />
      <path d="M10 15v2M14 15v2M8 21h8M9 17h6l1 4H8l1-4Z" />
    </svg>
  );
}

export function PeriodChip({ effort }: { effort: QualifyingRecentEffort }) {
  return (
    <div style={{ display: "flex", gap: 4 }}>
      {RECENT_TOP_EFFORT_PERIODS.map((p) => {
        const rank = effort.ranks[p.key];
        const top = rank != null && rank <= 3;
        const color = top ? MEDAL_COLORS[rank as 1 | 2 | 3] : "var(--line)";
        return (
          <span
            key={p.key}
            title={`${p.label}: ${rank != null ? `rank ${rank}` : "not ranked"}`}
            className="mono"
            style={{
              fontSize: 10.5,
              fontWeight: 700,
              whiteSpace: "nowrap",
              padding: "2px 7px",
              borderRadius: 5,
              color: top ? MEDAL_INK : "var(--ink3)",
              background: top ? MEDAL_COLORS[rank as 1 | 2 | 3] : "transparent",
              border: `1px solid ${color}`,
            }}
          >
            {p.label} {top ? `#${rank}` : "—"}
          </span>
        );
      })}
    </div>
  );
}

export function EffortRow({ effort }: { effort: QualifyingRecentEffort }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
      <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)", minWidth: 96 }}>{windowLabel(effort.window)}</span>
      <span className="mono" style={{ fontSize: 13, fontWeight: 700, color: "var(--ink)", minWidth: 64 }}>
        {formatBestEffortValue(effort.kind, effort.window, effort.value)}
      </span>
      <PeriodChip effort={effort} />
    </div>
  );
}

/** Renders `efforts` grouped by family (Power/Pace/Heart rate/Distance/Elevation, in that
 * order), capped to RECENT_TOP_EFFORTS_CAP with a "+N more effort(s)" expander that toggles to
 * "Show fewer" - the design handoff's cap rule, applied per-activity there and to the whole
 * list here. The expander's left offset (94px = the family grid's 84px label column + 10px
 * gap) aligns it with the metric column regardless of what wraps this fragment. */
export function FamilyGroupedEffortList({ efforts }: { efforts: QualifyingRecentEffort[] }) {
  const [open, setOpen] = useState(false);
  const hasMore = efforts.length > RECENT_TOP_EFFORTS_CAP;
  const hiddenCount = efforts.length - RECENT_TOP_EFFORTS_CAP;
  const shown = open ? efforts : efforts.slice(0, RECENT_TOP_EFFORTS_CAP);
  const families = BEST_EFFORT_FAMILY_ORDER.filter((f) => shown.some((e) => BEST_EFFORT_FAMILY[e.kind] === f));

  return (
    <>
      {families.map((family) => (
        <div key={family} style={{ display: "grid", gridTemplateColumns: "84px minmax(0,1fr)", gap: 10 }}>
          <div
            className="mono"
            style={{ fontSize: 10, fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.08em", color: "var(--ink3)", paddingTop: 4 }}
          >
            {family}
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            {shown
              .filter((e) => BEST_EFFORT_FAMILY[e.kind] === family)
              .map((e) => (
                <EffortRow key={`${e.kind}-${e.window}`} effort={e} />
              ))}
          </div>
        </div>
      ))}
      {hasMore && (
        <div
          onClick={() => setOpen((o) => !o)}
          style={{ fontSize: 12, fontWeight: 600, color: "var(--ember)", cursor: "pointer", marginLeft: 94 }}
        >
          {open ? "Show fewer" : `+${hiddenCount} more effort${hiddenCount === 1 ? "" : "s"}`}
        </div>
      )}
    </>
  );
}
