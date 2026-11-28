import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { excludeFromThresholds, includeInThresholds } from "../api/athletes";
import { getContexts } from "../api/auth";
import { useAuth } from "../auth/AuthContext";
import {
  ApiError,
  type Sport,
  type ThresholdExclusionEffect,
  type ThresholdExclusionLedgerRow,
  type ThresholdFieldName,
} from "../api/types";

/** Issue #616: excluding an activity from threshold detection (or including it again) - the
 * preview-then-confirm flow shared by the activity page's ThresholdExclusionControl and the
 * threshold history screen's "Excluded activities" list. A change is never made blind: the first
 * click runs it as a preview (nothing saved), and only confirming the diff it returns applies it. */

export const FIELD_LABELS: Record<ThresholdFieldName, string> = {
  ftp: "FTP",
  critical_run_power: "critical running power",
  threshold_pace: "threshold pace",
};

/** The fields an activity of each sport can define - the only ones it can be excluded from (the
 * backend 400s any other). */
export const EXCLUDABLE_FIELDS: Partial<Record<Sport, ThresholdFieldName[]>> = {
  bike: ["ftp"],
  run: ["critical_run_power", "threshold_pace"],
};

export function formatValue(field: ThresholdFieldName, value: number | string | null): string {
  if (value == null) {
    return "not set";
  }
  return field === "threshold_pace" ? `${value}/km` : `${value} W`;
}

export function formatDay(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

const KIND_LABELS: Record<ThresholdExclusionLedgerRow["kind"], string> = {
  measured: "measured",
  manual: "set manually",
  decayed: "decayed",
};

export function describeRow(field: ThresholdFieldName, row: ThresholdExclusionLedgerRow, activityId: string): string {
  const source = row.source_activity_id === activityId ? " (this activity)" : "";
  return `${formatValue(field, row.value)} ${KIND_LABELS[row.kind]}${source}, from ${formatDay(row.current_from)}`;
}

/** Whether the signed-in user may change the active athlete's data - false only for a coach
 * account viewing an athlete with the "viewer" role. Same rule ThresholdHistoryScreen uses. */
export function useCanWriteActiveAthlete(): boolean {
  const { activeAthleteId, isCoachAccount } = useAuth();
  const contextsQuery = useQuery({ queryKey: ["contexts"], queryFn: getContexts, enabled: isCoachAccount });
  return !activeAthleteId || contextsQuery.data?.coaching.find((c) => c.user_id === activeAthleteId)?.role !== "viewer";
}

export interface PendingChange {
  field: ThresholdFieldName;
  activityId: string;
  /** True to exclude, false to include again. */
  exclude: boolean;
  effect: ThresholdExclusionEffect;
}

/** Drives one change at a time: {@link start} previews it, {@link confirm} applies the previewed
 * change, {@link cancel} drops it. `notice` reports the outcome of the last apply - including a
 * request that timed out, which on an old activity can happen while the server is still finishing
 * (the rebuild re-rates every later activity), so the change may well land anyway. */
export function useThresholdExclusionChange(athleteId: string) {
  const qc = useQueryClient();
  const [pending, setPending] = useState<PendingChange | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const call = (field: ThresholdFieldName, activityId: string, exclude: boolean, preview: boolean) =>
    exclude
      ? excludeFromThresholds(athleteId, field, activityId, preview)
      : includeInThresholds(athleteId, field, activityId, preview);

  const invalidate = (field: ThresholdFieldName, activityId: string) => {
    qc.invalidateQueries({ queryKey: ["threshold-exclusions", athleteId] });
    qc.invalidateQueries({ queryKey: ["thresholds", athleteId] });
    qc.invalidateQueries({ queryKey: ["threshold-history", athleteId, field] });
    qc.invalidateQueries({ queryKey: ["threshold-suggestions", athleteId] });
    qc.invalidateQueries({ queryKey: ["zones", athleteId] });
    // The activity's own threshold_history indicator, plus every later activity's re-rated TSS.
    qc.invalidateQueries({ queryKey: ["activity", activityId] });
    qc.invalidateQueries({ queryKey: ["activities"] });
  };

  const previewMutation = useMutation({
    mutationFn: (target: { field: ThresholdFieldName; activityId: string; exclude: boolean }) =>
      call(target.field, target.activityId, target.exclude, true).then((effect) => ({ ...target, effect })),
    onMutate: () => setNotice(null),
    onSuccess: (change) => setPending(change),
    onError: (error) => setNotice(error instanceof ApiError ? error.message : "Couldn't preview that change."),
  });

  const applyMutation = useMutation({
    mutationFn: (change: PendingChange) => call(change.field, change.activityId, change.exclude, false).then(() => change),
    onSuccess: (change) => {
      setPending(null);
      setNotice(null);
      invalidate(change.field, change.activityId);
    },
    onError: (error, change) => {
      setPending(null);
      // A 4xx is a real refusal. Anything else - a gateway timeout, a dropped connection - may
      // just mean the request outlived the proxy while the server carries on.
      setNotice(
        error instanceof ApiError && error.status < 500
          ? error.message
          : "This is taking a while - the change may still be finishing. Check again in a couple of minutes.",
      );
      invalidate(change.field, change.activityId);
    },
  });

  return {
    pending,
    notice,
    previewing: previewMutation.isPending ? previewMutation.variables : null,
    applying: applyMutation.isPending,
    start: (field: ThresholdFieldName, activityId: string, exclude: boolean) => previewMutation.mutate({ field, activityId, exclude }),
    confirm: () => pending && applyMutation.mutate(pending),
    cancel: () => setPending(null),
  };
}

