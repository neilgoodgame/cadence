import { Link } from "react-router-dom";
import type { Activity, ThresholdFieldName } from "../../api/types";

const FIELD_LABELS: Record<ThresholdFieldName, string> = {
  ftp: "FTP",
  critical_run_power: "critical running power",
  threshold_pace: "threshold pace",
};

function formatValue(field: ThresholdFieldName, value: number | string): string {
  return field === "threshold_pace" ? `${value}/km` : `${value}W`;
}

const ROW_STYLE: React.CSSProperties = {
  fontSize: 13,
  color: "var(--ink2)",
  display: "flex",
  alignItems: "center",
  gap: 10,
  background: "var(--elev)",
  border: "1px solid var(--line)",
  borderRadius: 8,
  padding: "8px 12px",
};

const LINK_STYLE: React.CSSProperties = { color: "var(--ember)", fontWeight: 600, fontSize: 12, whiteSpace: "nowrap" };

/** Shown above the tabs when this activity is (or once was) the source of a ThresholdHistory
 * ledger entry, OR when this activity's own ingest/recompute pass is what revealed a
 * *different*, earlier activity's dormant effort as the new current value (source_activity_id
 * differs from this activity's own id - see ThresholdHistoryEntry) - the natural replacement for
 * the old suggestion banner's slot, just informational instead of actionable: this screen no
 * longer offers to accept/dismiss anything, since the ledger updates itself automatically as
 * activities are ingested (see athletes/threshold_history.py::recompute_for_activity). Renders
 * nothing for the vast majority of activities. */
export function ThresholdHistoryIndicator({ activity }: { activity: Activity }) {
  if (activity.threshold_history.length === 0) {
    return null;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {activity.threshold_history.map((entry) => {
        const revealed = entry.source_activity_id !== null && entry.source_activity_id !== activity.id;
        return (
          <div key={`${entry.field}-${entry.source_activity_id ?? "manual"}`} style={ROW_STYLE}>
            <span style={{ flex: 1 }}>
              {revealed ? (
                <>
                  Importing this activity revealed an earlier effort as your{" "}
                  {entry.is_current ? "current" : "previous"} {FIELD_LABELS[entry.field]}: {formatValue(entry.field, entry.value)}
                  {!entry.is_current && ", since superseded"}.
                </>
              ) : (
                <>
                  {entry.is_current ? "Currently defines" : "Previously defined"} your {FIELD_LABELS[entry.field]}:{" "}
                  {formatValue(entry.field, entry.value)}
                  {!entry.is_current && ", since superseded"}.
                </>
              )}
            </span>
            {revealed && (
              <Link to={`/activities/${entry.source_activity_id}`} style={LINK_STYLE}>
                View effort
              </Link>
            )}
            <Link to={`/thresholds/${entry.field}`} style={LINK_STYLE}>
              View history
            </Link>
          </div>
        );
      })}
    </div>
  );
}
