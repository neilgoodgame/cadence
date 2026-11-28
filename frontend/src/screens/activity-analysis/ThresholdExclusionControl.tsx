import { useQuery } from "@tanstack/react-query";
import { listThresholdExclusions } from "../../api/athletes";
import type { Activity } from "../../api/types";
import {
  EXCLUDABLE_FIELDS,
  FIELD_LABELS,
  useCanWriteActiveAthlete,
  useThresholdExclusionChange,
} from "../thresholdExclusions";
import { ThresholdExclusionConfirm } from "../ThresholdExclusionConfirm";

/** Issue #616: per field this activity could define, whether it's excluded from threshold
 * detection - and, with write access, a checkbox to exclude it (or include it again). Ticking
 * one previews the change first; nothing is saved until the diff is confirmed. Renders nothing
 * for a sport that defines no threshold, or for a duplicate copy (already left out of every scan). */
export function ThresholdExclusionControl({ activity, athleteId }: { activity: Activity; athleteId: string }) {
  const fields = EXCLUDABLE_FIELDS[activity.sport] ?? [];
  const canWrite = useCanWriteActiveAthlete();
  const change = useThresholdExclusionChange(athleteId);
  const visible = fields.length > 0 && !activity.primary_activity_id;

  const exclusionsQuery = useQuery({
    queryKey: ["threshold-exclusions", athleteId, { activityId: activity.id }],
    queryFn: () => listThresholdExclusions(athleteId, { activityId: activity.id }),
    enabled: visible,
  });

  if (!visible || !exclusionsQuery.data) {
    return null;
  }
  const excluded = new Set(exclusionsQuery.data.data.map((e) => e.field));
  if (!canWrite && excluded.size === 0) {
    return null;
  }
  const pending = change.pending?.activityId === activity.id ? change.pending : null;
  const busy = change.previewing !== null || change.applying;

  return (
    <div
      style={{
        fontSize: 13,
        color: "var(--ink2)",
        background: "var(--elev)",
        border: "1px solid var(--line)",
        borderRadius: 8,
        padding: "8px 12px",
        display: "flex",
        flexDirection: "column",
        gap: 8,
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 16, flexWrap: "wrap" }}>
        <span style={{ fontWeight: 600, color: "var(--ink)" }}>Threshold detection</span>
        {canWrite
          ? fields.map((field) => (
              <label key={field} style={{ display: "flex", alignItems: "center", gap: 6, cursor: busy ? "default" : "pointer" }}>
                <input
                  type="checkbox"
                  checked={excluded.has(field)}
                  disabled={busy || pending !== null}
                  onChange={() => change.start(field, activity.id, !excluded.has(field))}
                />
                Exclude from {FIELD_LABELS[field]}
              </label>
            ))
          : fields
              .filter((field) => excluded.has(field))
              .map((field) => <span key={field}>Excluded from {FIELD_LABELS[field]}</span>)}
        {change.previewing && <span style={{ color: "var(--ink3)" }}>Working out what would change…</span>}
      </div>
      {pending && (
        <ThresholdExclusionConfirm change={pending} applying={change.applying} onConfirm={change.confirm} onCancel={change.cancel} />
      )}
      {change.notice && <div style={{ fontSize: 12, color: "var(--ink3)" }}>{change.notice}</div>}
    </div>
  );
}
