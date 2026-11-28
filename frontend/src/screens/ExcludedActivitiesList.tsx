import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { listThresholdExclusions } from "../api/athletes";
import type { ThresholdFieldName } from "../api/types";
import {
  FIELD_LABELS,
  formatDay,
  useCanWriteActiveAthlete,
  useThresholdExclusionChange,
} from "./thresholdExclusions";
import { ThresholdExclusionConfirm } from "./ThresholdExclusionConfirm";

/** Issue #616: the activities excluded from this field's threshold detection, on the threshold
 * history screen - none of them can appear in the ledger above, so this is where their state
 * shows. With write access each can be included again, previewed first like the activity page's
 * ThresholdExclusionControl. Renders nothing when there are none. */
export function ExcludedActivitiesList({ athleteId, field }: { athleteId: string; field: ThresholdFieldName }) {
  const canWrite = useCanWriteActiveAthlete();
  const change = useThresholdExclusionChange(athleteId);
  const exclusionsQuery = useQuery({
    queryKey: ["threshold-exclusions", athleteId, { field }],
    queryFn: () => listThresholdExclusions(athleteId, { field }),
  });

  const exclusions = exclusionsQuery.data?.data ?? [];
  if (exclusions.length === 0 && !change.notice) {
    return null;
  }
  const busy = change.previewing !== null || change.applying || change.pending !== null;

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, overflow: "hidden" }}>
      <div style={{ padding: "14px 18px", borderBottom: "1px solid var(--line)" }}>
        <span className="mono" style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", color: "var(--ink3)" }}>
          EXCLUDED ACTIVITIES
        </span>
        <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 4 }}>
          These never count towards your {FIELD_LABELS[field]}, for example because their data is known to be wrong.
        </div>
      </div>
      {exclusions.map((exclusion) => {
        const pending = change.pending?.activityId === exclusion.activity_id ? change.pending : null;
        return (
          <div key={exclusion.activity_id} style={{ padding: "10px 18px", borderTop: "1px solid var(--line)", display: "flex", flexDirection: "column", gap: 8 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 12, fontSize: 13 }}>
              <Link to={`/activities/${exclusion.activity_id}`} style={{ color: "var(--ink)", fontWeight: 600, flex: 1 }}>
                {exclusion.activity_name}
              </Link>
              <span className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>
                {formatDay(exclusion.activity_date)}
              </span>
              {canWrite && (
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => change.start(field, exclusion.activity_id, false)}
                  style={{ fontSize: 12, fontWeight: 600, color: "var(--ember)", background: "none", border: "none", cursor: busy ? "default" : "pointer", padding: 0 }}
                >
                  {change.previewing?.activityId === exclusion.activity_id ? "Checking…" : "Include again"}
                </button>
              )}
            </div>
            {pending && (
              <ThresholdExclusionConfirm change={pending} applying={change.applying} onConfirm={change.confirm} onCancel={change.cancel} />
            )}
          </div>
        );
      })}
      {change.notice && <div style={{ padding: "10px 18px", fontSize: 12, color: "var(--ink3)", borderTop: "1px solid var(--line)" }}>{change.notice}</div>}
    </div>
  );
}
