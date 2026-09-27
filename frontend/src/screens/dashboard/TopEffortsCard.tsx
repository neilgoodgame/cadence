import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import type { Activity } from "../../api/types";
import { getActivityBestEffortRanks } from "../../api/athletes";
import { Card } from "../../components/Card";
import {
  BEST_EFFORT_FAMILY,
  BEST_EFFORT_FAMILY_ORDER,
  MEDAL_COLORS,
  MEDAL_INK,
  RECENT_TOP_EFFORTS_CAP,
  RECENT_TOP_EFFORT_PERIODS,
  formatBestEffortValue,
  groupRecentEffortsByActivity,
  qualifyingRecentEfforts,
  windowLabel,
  type QualifyingRecentEffort,
  type RecentTopEffortGroup,
} from "../../lib/bestEfforts";
import { formatDate } from "../../lib/format";
import { sportColor } from "../../lib/sportColors";

function TrophyIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#c9981a" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 3h8v5a4 4 0 0 1-8 0V3Z" />
      <path d="M8 4H5a2 2 0 0 0 0 4h1.5M16 4h3a2 2 0 0 1 0 4h-1.5" />
      <path d="M10 15v2M14 15v2M8 21h8M9 17h6l1 4H8l1-4Z" />
    </svg>
  );
}

function PeriodChip({ effort }: { effort: QualifyingRecentEffort }) {
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

function EffortRow({ effort }: { effort: QualifyingRecentEffort }) {
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

function ActivityBlock({ group, activity }: { group: RecentTopEffortGroup; activity: Activity | null }) {
  const [open, setOpen] = useState(false);
  const best = group.items[0];
  const hasMore = group.items.length > RECENT_TOP_EFFORTS_CAP;
  const hiddenCount = group.items.length - RECENT_TOP_EFFORTS_CAP;
  const shown = open ? group.items : group.items.slice(0, RECENT_TOP_EFFORTS_CAP);
  const families = BEST_EFFORT_FAMILY_ORDER.filter((f) => shown.some((e) => BEST_EFFORT_FAMILY[e.kind] === f));
  const sport = activity?.sport ?? best.sport;

  return (
    <div style={{ borderTop: "1px solid var(--line)", padding: "12px 6px", display: "flex", gap: 14 }}>
      <div
        className="mono"
        style={{
          width: 30,
          height: 30,
          borderRadius: "50%",
          flexShrink: 0,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontSize: 12,
          fontWeight: 800,
          color: MEDAL_INK,
          background: MEDAL_COLORS[best.hl.rank as 1 | 2 | 3],
        }}
      >
        #{best.hl.rank}
      </div>
      <div style={{ width: 4, alignSelf: "stretch", borderRadius: 3, flexShrink: 0, background: sportColor(sport) }} />
      <div style={{ display: "flex", flexDirection: "column", gap: 8, flex: 1, minWidth: 0 }}>
        <Link
          to={`/activities/${group.activityId}`}
          style={{ display: "flex", alignItems: "baseline", gap: 8, flexWrap: "wrap", textDecoration: "none" }}
        >
          <span style={{ fontSize: 14, fontWeight: 700, color: "var(--ink)" }}>{activity?.name ?? "Activity"}</span>
          {activity && (
            <span className="mono" style={{ fontSize: 11.5, color: "var(--ink3)" }}>
              {formatDate(activity.start_date)}
            </span>
          )}
          <span
            className="mono"
            style={{ fontSize: 10.5, fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.06em", color: "var(--ink2)" }}
          >
            {best.hl.adj} #{best.hl.rank} · {group.items.length} effort{group.items.length === 1 ? "" : "s"}
          </span>
        </Link>

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
      </div>
    </div>
  );
}

/** Dashboard card highlighting any activity from the last 7 days that ranks top-3 in some
 * Best Efforts leaderboard (16-week, 1-year, or all-time) - see the design handoff's rules.
 * Renders nothing while loading, on error, or when nothing qualifies (additive UI, never a
 * loading/error state of its own - see the handoff's States section), which the `?? []`
 * fallback below achieves without any extra branching. */
export function TopEffortsCard({
  athleteId,
  activities,
  recentActivityIds,
}: {
  athleteId: string;
  activities: Activity[];
  recentActivityIds: string[];
}) {
  const { data } = useQuery({
    queryKey: ["best-efforts", "ranks", athleteId, recentActivityIds],
    queryFn: () => getActivityBestEffortRanks(athleteId, recentActivityIds),
    enabled: !!athleteId,
  });

  const list = qualifyingRecentEfforts(data?.data ?? []);
  if (list.length === 0) return null;

  const groups = groupRecentEffortsByActivity(list);
  const activityById = new Map(activities.map((a) => [a.id, a]));

  const familyCounts = BEST_EFFORT_FAMILY_ORDER
    .map((f) => [f, list.filter((e) => BEST_EFFORT_FAMILY[e.kind] === f).length] as const)
    .filter(([, n]) => n > 0);
  const summary =
    `${list.length} top-3 effort${list.length === 1 ? "" : "s"} across ${groups.length} ` +
    `activit${groups.length === 1 ? "y" : "ies"} in the last 7 days · ` +
    familyCounts.map(([f, n]) => `${f} ${n}`).join(" · ");

  return (
    <Card>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 12, marginBottom: 6 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <TrophyIcon />
          <h2 style={{ fontSize: 16, fontWeight: 700, letterSpacing: "-0.01em", margin: 0, color: "var(--ink)" }}>
            Top efforts this week
          </h2>
        </div>
        <Link to="/best-efforts" style={{ fontSize: 13, fontWeight: 600, color: "var(--ember)", textDecoration: "none" }}>
          Best Efforts →
        </Link>
      </div>

      <div style={{ fontSize: 12.5, color: "var(--ink3)", marginBottom: 10 }}>{summary}</div>

      {groups.map((group) => (
        <ActivityBlock key={group.activityId} group={group} activity={activityById.get(group.activityId) ?? null} />
      ))}
    </Card>
  );
}
