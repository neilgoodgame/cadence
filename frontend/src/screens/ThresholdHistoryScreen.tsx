import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getThresholdHistory } from "../api/athletes";
import { formatDate, formatPace } from "../lib/format";
import { useAuth } from "../auth/AuthContext";
import type { ThresholdFieldName } from "../api/types";

const FIELD_LABELS: Record<ThresholdFieldName, string> = {
  ftp: "FTP",
  critical_run_power: "Critical running power",
  threshold_pace: "Threshold pace",
};

function formatValue(field: ThresholdFieldName, value: number | string): string {
  return field === "threshold_pace" ? formatPace(Number(value)) : `${value}W`;
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

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20, maxWidth: 560 }}>
      <div>
        <Link to="/" style={{ fontSize: 13, color: "var(--ink3)" }}>
          &larr; Back to dashboard
        </Link>
        <h1 style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.02em", margin: "8px 0 0" }}>
          {FIELD_LABELS[field]} history
        </h1>
      </div>

      {historyQuery.isLoading && <div style={{ color: "var(--ink3)" }}>Loading…</div>}

      {!historyQuery.isLoading && entries.length === 0 && (
        <div style={{ color: "var(--ink3)", fontSize: 13 }}>
          No history yet - this fills in as qualifying activities are uploaded, or via a bulk
          rebuild in Preferences &rsaquo; Best efforts.
        </div>
      )}

      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {entries.map((entry, i) => (
          <div
            key={`${entry.effective_from}-${i}`}
            style={{
              display: "flex", alignItems: "center", justifyContent: "space-between",
              padding: "10px 14px", borderRadius: 8, background: "var(--elev)", border: "1px solid var(--line)",
            }}
          >
            <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
              <span className="mono" style={{ fontSize: 16, fontWeight: 600, color: "var(--ink)" }}>
                {formatValue(field, entry.value)}
              </span>
              <span style={{ fontSize: 12, color: "var(--ink3)" }}>Effective from {formatDate(entry.effective_from)}</span>
            </div>
            {entry.source_activity_id && (
              <Link to={`/activities/${entry.source_activity_id}`} style={{ fontSize: 12, color: "var(--ember)", fontWeight: 600 }}>
                View activity
              </Link>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
