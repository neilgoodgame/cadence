import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getActivity, listActivityOverlaps, updateActivity } from "../../api/activities";
import type { Activity } from "../../api/types";
import { Card } from "../../components/Card";
import { formatDuration } from "../../lib/format";

/** Banner shown on a duplicate recording's own page: points at the primary and
 * can promote this recording to be the one that counts for training load. */
export function DuplicateBanner({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  const primaryId = activity.primary_activity_id!;

  const { data: primary } = useQuery({
    queryKey: ["activity", primaryId],
    queryFn: () => getActivity(primaryId),
  });

  async function makeThisPrimary() {
    if (!primary) return;
    setBusy(true);
    try {
      // Free this recording, re-point the group's other duplicates at it, then
      // demote the old primary. Ordered so the no-chain rule never trips.
      await updateActivity(activity.id, { primary_activity_id: null });
      for (const otherId of primary.duplicate_activity_ids.filter((id) => id !== activity.id)) {
        await updateActivity(otherId, { primary_activity_id: activity.id });
      }
      await updateActivity(primaryId, { primary_activity_id: activity.id });
    } finally {
      setBusy(false);
      queryClient.invalidateQueries();
    }
  }

  return (
    <div style={{ fontSize: 13, color: "var(--ink2)", display: "flex", alignItems: "center", gap: 10 }}>
      <span>
        This is a duplicate recording — training load is counted from{" "}
        <Link to={`/activities/${primaryId}`} style={{ color: "var(--ember)", fontWeight: 600 }}>
          {primary ? primary.name : "the primary activity"}
        </Link>
      </span>
      <button
        onClick={makeThisPrimary}
        disabled={busy || !primary}
        style={{
          border: "1px solid var(--line)",
          borderRadius: 8,
          padding: "4px 10px",
          fontSize: 12,
          fontWeight: 600,
          background: "transparent",
          color: "var(--ink2)",
          cursor: busy ? "wait" : "pointer",
        }}
      >
        Make this the primary
      </button>
    </div>
  );
}

/**
 * Duplicate recordings of the same session (e.g. Zwift + a head unit). Shown on any
 * activity that could be a duplicate group's primary: lists the linked duplicates
 * (unlink / make primary) and offers nearby same-sport activities to link. Only the
 * primary counts toward training load, so linked duplicates drop out of the activity
 * list, calendar, and fitness charts until unlinked.
 */
export function DuplicatesCard({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const duplicateIds = activity.duplicate_activity_ids;

  // Issue #498's overlap-detection backbone: pins this activity as anchor and returns every
  // other activity whose recorded interval overlaps it, already excluding resolved pairs
  // (includeResolved defaults to false) - so everything back is a genuine "maybe a duplicate"
  // candidate, not just a same-day guess.
  const overlapsQuery = useQuery({
    queryKey: ["duplicate-candidates", activity.id],
    queryFn: () => listActivityOverlaps({ activityId: activity.id, limit: 20 }),
  });
  const candidatePairs = overlapsQuery.data?.data ?? [];

  const { data: duplicates } = useQuery({
    queryKey: ["duplicates", activity.id, duplicateIds.join()],
    queryFn: () => Promise.all(duplicateIds.map((id) => getActivity(id))),
    enabled: duplicateIds.length > 0,
  });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["activity"] });
    queryClient.invalidateQueries({ queryKey: ["activities"] });
    queryClient.invalidateQueries({ queryKey: ["duplicates"] });
    queryClient.invalidateQueries({ queryKey: ["duplicate-candidates"] });
  };

  const mutate = useMutation({
    mutationFn: async (action: () => Promise<unknown>) => action(),
    onMutate: () => {
      setBusy(true);
      setError(null);
    },
    onError: (e: Error) => setError(e.message),
    onSettled: () => {
      setBusy(false);
      refresh();
    },
  });

  const linkDuplicate = (candidateId: string) =>
    mutate.mutate(() => updateActivity(candidateId, { primary_activity_id: activity.id }));

  const unlinkDuplicate = (duplicateId: string) =>
    mutate.mutate(() => updateActivity(duplicateId, { primary_activity_id: null }));

  // Promote a duplicate to primary: free it, re-point the rest of the group at it,
  // then demote the current primary. Ordered so the no-chain rule never trips.
  const makePrimary = (duplicateId: string) =>
    mutate.mutate(async () => {
      await updateActivity(duplicateId, { primary_activity_id: null });
      for (const otherId of duplicateIds.filter((id) => id !== duplicateId)) {
        await updateActivity(otherId, { primary_activity_id: duplicateId });
      }
      await updateActivity(activity.id, { primary_activity_id: duplicateId });
    });

  if (duplicateIds.length === 0 && candidatePairs.length === 0) {
    return null;
  }

  const buttonStyle: React.CSSProperties = {
    border: "1px solid var(--line)",
    borderRadius: 8,
    padding: "4px 10px",
    fontSize: 12,
    fontWeight: 600,
    background: "transparent",
    color: "var(--ink2)",
    cursor: busy ? "wait" : "pointer",
  };

  return (
    <Card>
      <div style={{ fontSize: 12, color: "var(--ink3)", marginBottom: 12 }}>Duplicate recordings</div>

      {duplicateIds.length > 0 && (
        <div style={{ display: "flex", flexDirection: "column" }}>
          {duplicateIds.map((id, i) => {
            const dup = duplicates?.find((d) => d.id === id);
            return (
              <div
                key={id}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 12,
                  padding: "10px 0",
                  borderTop: i > 0 ? "1px solid var(--line)" : "none",
                }}
              >
                <Link to={`/activities/${id}`} style={{ flex: 1, fontSize: 13, color: "var(--ink)", fontWeight: 600 }}>
                  {dup ? dup.name : id}
                </Link>
                <span style={{ fontSize: 12, color: "var(--ink3)" }}>
                  {dup ? `${dup.device || dup.source || "unknown source"} · ${formatDuration(dup.moving_time)}` : ""}
                </span>
                <button style={buttonStyle} disabled={busy} onClick={() => makePrimary(id)}>
                  Make primary
                </button>
                <button style={buttonStyle} disabled={busy} onClick={() => unlinkDuplicate(id)}>
                  Unlink
                </button>
              </div>
            );
          })}
        </div>
      )}

      {candidatePairs.length > 0 && (
        <div style={{ marginTop: duplicateIds.length > 0 ? 14 : 0 }}>
          <div style={{ fontSize: 12, color: "var(--ink3)", marginBottom: 6 }}>
            Overlapping activities that may be duplicates of this one
          </div>
          {candidatePairs.map((pair) => {
            const candidate = pair.other;
            const details = [
              candidate.device || "unknown source",
              candidate.elapsed_seconds != null ? formatDuration(candidate.elapsed_seconds) : null,
              `${Math.round(pair.overlap_pct)}% overlap`,
              pair.avg_power_ratio != null ? `${pair.avg_power_ratio.toFixed(2)}x power` : null,
            ]
              .filter(Boolean)
              .join(" · ");
            return (
              <div key={candidate.id} style={{ display: "flex", alignItems: "center", gap: 12, padding: "6px 0" }}>
                <Link to={`/activities/${candidate.id}`} style={{ flex: 1, fontSize: 13, color: "var(--ink2)" }}>
                  {candidate.name}
                </Link>
                <span style={{ fontSize: 12, color: "var(--ink3)" }}>{details}</span>
                <button style={buttonStyle} disabled={busy} onClick={() => linkDuplicate(candidate.id)}>
                  Link as duplicate
                </button>
              </div>
            );
          })}
        </div>
      )}

      {error && (
        <div style={{ marginTop: 10, fontSize: 12, color: "var(--danger, #c0392b)" }}>{error}</div>
      )}
    </Card>
  );
}
