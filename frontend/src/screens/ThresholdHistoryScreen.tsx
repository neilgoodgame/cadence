import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getThresholdHistory } from "../api/athletes";
import { useAuth } from "../auth/AuthContext";
import { Card } from "../components/Card";
import { ActivityNameLink } from "../components/ActivityNameLink";
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

function fmtShortDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

const GRID_COLS = "28px minmax(90px,0.8fr) minmax(160px,1.4fr) minmax(115px,0.95fr) minmax(105px,0.85fr) minmax(140px,1.1fr) minmax(115px,0.95fr)";

/** The full ledger for one threshold field, most recent first - reachable only via the
 * dashboard's ThresholdSummaryCard links (not in the sidebar nav), matching /activities/:id's
 * own direct-link-only pattern. Laid out as a wide table (matching BestEffortsScreen's own
 * CardShell/ColHeaders/grid-row convention) rather than a narrow row list, so the ledger's full
 * detail - which activity, the delta vs the previous entry, whether a later ingest revealed this
 * entry after the fact - is visible without leaving the page. */
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
  const maxDays = Math.max(1, ...days);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
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

      {entries.length > 0 && (
        <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, overflow: "hidden" }}>
          <div
            style={{
              display: "flex", alignItems: "center", justifyContent: "space-between",
              padding: "14px 18px", borderBottom: "1px solid var(--line)",
            }}
          >
            <span className="mono" style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", color: "var(--ink3)" }}>
              {FIELD_LABELS[field].toUpperCase()} &middot; LEDGER
            </span>
            <span style={{ fontSize: 12, color: "var(--ink3)" }}>
              {entries.length} entr{entries.length === 1 ? "y" : "ies"}
            </span>
          </div>

          <div
            className="mono"
            style={{
              display: "grid", gridTemplateColumns: GRID_COLS, gap: 8,
              padding: "10px 18px", borderBottom: "1px solid var(--line)",
              fontSize: 10, textTransform: "uppercase", letterSpacing: "0.06em",
              color: "var(--ink3)", whiteSpace: "nowrap",
            }}
          >
            <span>#</span>
            <span>Value</span>
            <span>Activity</span>
            <span>&Delta; vs previous</span>
            <span>Effective from</span>
            <span>Days in effect</span>
            <span>Effort date</span>
          </div>

          {entries.map((entry, i) => {
            // effective_from is the qualifying activity's own date; current_from is when this
            // row actually became the recorded current value (see ThresholdHistoryPoint) - they
            // differ exactly when a later ingest revealed an earlier, dormant effort (the same
            // "revealed" case ThresholdHistoryIndicator surfaces on the activity page).
            const revealed = entry.effective_from !== entry.current_from;
            const delta = i < entries.length - 1 ? deltaLabel(field, entry.value, entries[i + 1].value) : null;
            const barPct = Math.round((days[i] / maxDays) * 100);
            return (
              <div
                key={`${entry.current_from}-${i}`}
                style={{
                  display: "grid", gridTemplateColumns: GRID_COLS, gap: 8,
                  padding: "11px 18px",
                  borderBottom: i < entries.length - 1 ? "1px solid var(--line)" : "none",
                  alignItems: "center",
                }}
              >
                <span className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>{i + 1}</span>
                <span className="mono" style={{ fontSize: 15, fontWeight: 700, color: "var(--ink)" }}>
                  {formatValue(field, entry.value)}
                </span>
                {entry.source_activity_id ? (
                  <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ember)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                    <ActivityNameLink id={entry.source_activity_id} />
                  </span>
                ) : (
                  <span className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>&mdash;</span>
                )}
                <span className="mono" style={{ fontSize: 12, color: "var(--ink3)", whiteSpace: "nowrap" }}>
                  {delta ?? "—"}
                </span>
                <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>{fmtShortDate(entry.current_from)}</span>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <div style={{ width: 56, height: 6, borderRadius: 3, background: "var(--line)", overflow: "hidden", flexShrink: 0 }}>
                    <div style={{ width: `${barPct}%`, height: "100%", background: "var(--ember)", borderRadius: 3 }} />
                  </div>
                  <span className="mono" style={{ fontSize: 12, color: "var(--ink2)", whiteSpace: "nowrap" }}>
                    {days[i]} day{days[i] === 1 ? "" : "s"}
                  </span>
                </div>
                {revealed ? (
                  <span style={{ display: "inline-flex", alignItems: "baseline", gap: 6, whiteSpace: "nowrap" }}>
                    <span
                      className="mono"
                      style={{
                        fontSize: 9, fontWeight: 700, padding: "2px 7px", borderRadius: 20,
                        background: "var(--ember-soft)", color: "var(--ember)", letterSpacing: "0.04em",
                      }}
                    >
                      REVEALED
                    </span>
                    <span className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>{fmtShortDate(entry.effective_from)}</span>
                  </span>
                ) : (
                  <span className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>&mdash;</span>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
