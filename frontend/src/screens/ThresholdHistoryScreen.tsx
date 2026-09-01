import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getThresholdHistory } from "../api/athletes";
import { formatDate } from "../lib/format";
import { useAuth } from "../auth/AuthContext";
import { Card } from "../components/Card";
import { ThresholdHistoryChart } from "./ThresholdHistoryChart";
import { daysInEffect, deltaLabel } from "./thresholdHistory";
import type { ThresholdFieldName } from "../api/types";

const FIELD_LABELS: Record<ThresholdFieldName, string> = {
  ftp: "FTP",
  critical_run_power: "Critical running power",
  threshold_pace: "Threshold pace",
};

// entry.value is already "M:SS" for threshold_pace (matches the backend's value_pace field
// verbatim) - not seconds, so it's displayed as-is rather than run through formatPace (which
// expects a number of seconds, not a string - Number("4:30") is NaN).
function formatValue(field: ThresholdFieldName, value: number | string): string {
  return field === "threshold_pace" ? `${value}/km` : `${value}W`;
}

/** The full ledger for one threshold field, most recent first - reachable only via the
 * dashboard's ThresholdSummaryCard links (not in the sidebar nav), matching /activities/:id's
 * own direct-link-only pattern. */
export function ThresholdHistoryScreen() {
  const { field } = useParams<{ field: ThresholdFieldName }>();
  const { user } = useAuth();

  const historyQuery = useQuery({
    queryKey: ["threshold-history", user?.id, field],
    queryFn: () => getThresholdHistory(user!.id, field!),
    enabled: !!user && !!field,
  });

  if (!user || !field) {
    return null;
  }

  const entries = historyQuery.data?.data ?? [];
  const days = daysInEffect(entries);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <div style={{ maxWidth: 560 }}>
        <Link to="/" style={{ fontSize: 13, color: "var(--ink3)" }}>
          &larr; Back to dashboard
        </Link>
        <h1 style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.02em", margin: "8px 0 0" }}>
          {FIELD_LABELS[field]} history
        </h1>
      </div>

      {historyQuery.isLoading && <div style={{ color: "var(--ink3)" }}>Loading…</div>}

      {!historyQuery.isLoading && entries.length === 0 && (
        <div style={{ color: "var(--ink3)", fontSize: 13, maxWidth: 560 }}>
          No history yet - this fills in as qualifying activities are uploaded, or via a bulk
          rebuild in Preferences &rsaquo; Best efforts.
        </div>
      )}

      {entries.length > 0 && (
        <Card>
          <ThresholdHistoryChart field={field} points={entries} />
        </Card>
      )}

      <div style={{ display: "flex", flexDirection: "column", gap: 8, maxWidth: 560 }}>
        {entries.map((entry, i) => {
          // effective_from is the qualifying activity's own date; current_from is when this
          // row actually became the recorded current value (see ThresholdHistoryPoint) - they
          // differ exactly when a later ingest revealed an earlier, dormant effort (the same
          // "revealed" case ThresholdHistoryIndicator surfaces on the activity page).
          const revealed = entry.effective_from !== entry.current_from;
          const delta = i < entries.length - 1 ? deltaLabel(field, entry.value, entries[i + 1].value) : null;
          return (
            <div
              key={`${entry.current_from}-${i}`}
              style={{
                display: "flex", alignItems: "center", justifyContent: "space-between",
                padding: "10px 14px", borderRadius: 8, background: "var(--elev)", border: "1px solid var(--line)",
              }}
            >
              <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                <span className="mono" style={{ fontSize: 16, fontWeight: 600, color: "var(--ink)" }}>
                  {formatValue(field, entry.value)}
                </span>
                <span style={{ fontSize: 12, color: "var(--ink3)" }}>
                  Effective from {formatDate(entry.current_from, true)} &middot; {days[i]} day{days[i] === 1 ? "" : "s"}
                  {revealed && <> &middot; effort from {formatDate(entry.effective_from, true)}</>}
                </span>
              </div>
              <div style={{ display: "flex", alignItems: "center", gap: 14, flexShrink: 0 }}>
                {delta && <span style={{ fontSize: 12, color: "var(--ink3)", whiteSpace: "nowrap" }}>{delta}</span>}
                {entry.source_activity_id && (
                  <Link to={`/activities/${entry.source_activity_id}`} style={{ fontSize: 12, color: "var(--ember)", fontWeight: 600, whiteSpace: "nowrap" }}>
                    View activity
                  </Link>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
