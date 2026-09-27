import { useQuery } from "@tanstack/react-query";
import type { Activity } from "../../api/types";
import { getActivityBestEffortRanks } from "../../api/athletes";
import { FamilyGroupedEffortList, TrophyIcon } from "../../components/BestEffortRow";
import { qualifyingRecentEfforts } from "../../lib/bestEfforts";

/** This activity's own best-effort standings, wherever it ranks top-3 in a leaderboard
 * (16-week, 1-year, or all-time) - the per-activity counterpart to the Dashboard's "Top
 * efforts this week" card (dashboard/TopEffortsCard.tsx), scoped to just this one activity via
 * the same GET .../best-efforts/ranks endpoint (batched with a single id). Unlike the
 * Dashboard card, there's no per-activity name/date/rank-circle wrapper needed - the athlete is
 * already looking at this exact activity.
 *
 * Renders nothing while loading, on error, or when nothing qualifies - additive UI, same
 * convention as the Dashboard card. */
export function ActivityBestEffortsCard({ activity, athleteId }: { activity: Activity; athleteId: string }) {
  const { data } = useQuery({
    queryKey: ["best-efforts", "ranks", athleteId, [activity.id]],
    queryFn: () => getActivityBestEffortRanks(athleteId, [activity.id]),
    enabled: !!athleteId,
  });

  const list = qualifyingRecentEfforts(data?.data ?? []);
  if (list.length === 0) return null;

  const best = list[0];

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "16px 22px" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 10, flexWrap: "wrap" }}>
        <TrophyIcon />
        <span style={{ fontSize: 14, fontWeight: 700, color: "var(--ink)" }}>Top efforts</span>
        <span
          className="mono"
          style={{ fontSize: 10.5, fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.06em", color: "var(--ink2)" }}
        >
          {best.hl.adj} #{best.hl.rank} · {list.length} effort{list.length === 1 ? "" : "s"}
        </span>
      </div>

      <FamilyGroupedEffortList efforts={list} />
    </div>
  );
}
