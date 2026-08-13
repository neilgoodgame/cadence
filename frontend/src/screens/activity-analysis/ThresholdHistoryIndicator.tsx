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

/** Shown above the tabs when this activity is (or once was) the source of a ThresholdHistory
 * ledger entry - the natural replacement for the old suggestion banner's slot, just
 * informational instead of actionable: this screen no longer offers to accept/dismiss anything,
 * since the ledger updates itself automatically as activities are ingested (see
 * athletes/threshold_history.py::recompute_for_activity). Renders nothing for the vast majority
 * of activities. */
export function ThresholdHistoryIndicator({ activity }: { activity: Activity }) {
  if (activity.threshold_history.length === 0) {
    return null;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {activity.threshold_history.map((entry) => (
        <div key={entry.field} style={ROW_STYLE}>
          <span style={{ flex: 1 }}>
            {entry.is_current ? "Currently defines" : "Previously defined"} your {FIELD_LABELS[entry.field]}:{" "}
            {formatValue(entry.field, entry.value)}
            {!entry.is_current && ", since superseded"}.
          </span>
          <Link to={`/thresholds/${entry.field}`} style={{ color: "var(--ember)", fontWeight: 600, fontSize: 12, whiteSpace: "nowrap" }}>
            View history
          </Link>
        </div>
      ))}
    </div>
  );
}
