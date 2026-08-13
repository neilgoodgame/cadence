import { useQuery, useQueryClient, useMutation } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getThresholds, refreshThreshold } from "../../api/athletes";
import { Card } from "../../components/Card";
import { formatPace, parsePace } from "../../lib/format";
import { useAuth } from "../../auth/AuthContext";
import type { ThresholdFieldName, ThresholdSummaryEntry } from "../../api/types";

const FIELDS: { field: ThresholdFieldName; label: string; unit: string }[] = [
  { field: "ftp", label: "FTP", unit: "W" },
  { field: "critical_run_power", label: "Critical running power", unit: "W" },
  { field: "threshold_pace", label: "Threshold pace", unit: "" },
];

// ThresholdSummaryEntry.value is already "M:SS" for threshold_pace (matches the backend's
// value_pace field verbatim) - not seconds, so it's displayed as-is rather than run through
// formatPace (which expects a number of seconds, not a string).
function formatValue(field: ThresholdFieldName, value: number | string): string {
  return field === "threshold_pace" ? `${value}/km` : String(value);
}

// Mirrors the backend's own is_stale/isStale comparison (days between effective_from and today,
// vs threshold_window_days) so this always agrees with when the "Aged out of window" notice
// takes over. Parses effective_from as local midnight, not new Date(isoDate)'s UTC-midnight
// default, to avoid the off-by-one near midnight that bit WeekCalendar's date bucketing before.
function daysRemaining(effectiveFrom: string, windowDays: number): number {
  const effective = new Date(`${effectiveFrom}T00:00:00`);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const elapsedDays = Math.round((today.getTime() - effective.getTime()) / 86_400_000);
  return windowDays - elapsedDays;
}

// Pace is seconds/km - a *lower* value is the improvement, the opposite of the two power fields.
function deltaLabel(field: ThresholdFieldName, entry: ThresholdSummaryEntry): string | null {
  if (entry.value == null || entry.previous_value == null) return null;
  if (field === "threshold_pace") {
    const current = parsePace(String(entry.value));
    const previous = parsePace(String(entry.previous_value));
    if (current == null || previous == null) return null;
    const delta = current - previous;
    if (delta === 0) return "No change";
    return `${delta < 0 ? "▼" : "▲"} ${formatPace(Math.abs(delta))} vs previous`;
  }
  const delta = Number(entry.value) - Number(entry.previous_value);
  if (delta === 0) return "No change";
  return `${delta > 0 ? "+" : ""}${delta}W vs previous`;
}

function FieldCard({ field, label, unit, entry }: { field: ThresholdFieldName; label: string; unit: string; entry: ThresholdSummaryEntry }) {
  const { user } = useAuth();
  const qc = useQueryClient();
  const refreshMutation = useMutation({
    mutationFn: () => refreshThreshold(user!.id, field),
    onSuccess: (updated) => qc.setQueryData(["thresholds", user!.id], updated),
  });
  const delta = deltaLabel(field, entry);
  const remaining = !entry.stale && entry.value != null && entry.effective_from
    ? daysRemaining(entry.effective_from, user!.threshold_window_days)
    : null;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
      <div className="mono" style={{ fontSize: 11, letterSpacing: "0.06em", color: "var(--ink3)", fontWeight: 600 }}>
        {label.toUpperCase()}
      </div>
      <div style={{ display: "flex", alignItems: "baseline", gap: 6 }}>
        <span className="mono" style={{ fontSize: 25, fontWeight: 600, color: "var(--ink)" }}>
          {entry.value != null ? formatValue(field, entry.value) : "—"}
        </span>
        {unit && entry.value != null && <span style={{ fontSize: 13, fontWeight: 500, color: "var(--ink2)" }}>{unit}</span>}
      </div>
      {delta && <div style={{ fontSize: 12, color: "var(--ink3)" }}>{delta}</div>}
      {remaining != null && (
        <div style={{ fontSize: 11, color: "var(--ink3)" }}>
          Valid for {remaining} more day{remaining === 1 ? "" : "s"}
        </div>
      )}
      {entry.stale && (
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginTop: 2 }}>
          <span style={{ fontSize: 11, color: "var(--ember)" }}>
            {entry.value == null ? "No qualifying effort yet" : "Aged out of window"}
          </span>
          <button
            onClick={() => refreshMutation.mutate()}
            disabled={refreshMutation.isPending}
            style={{
              fontSize: 11, fontWeight: 600, padding: "2px 8px", borderRadius: 6,
              border: "1px solid var(--line)", background: "transparent", color: "var(--ink2)",
              cursor: refreshMutation.isPending ? "wait" : "pointer",
            }}
          >
            {refreshMutation.isPending ? "Refreshing…" : "Refresh"}
          </button>
        </div>
      )}
      <Link to={`/thresholds/${field}`} style={{ fontSize: 12, color: "var(--ember)", fontWeight: 600, marginTop: 4 }}>
        View history
      </Link>
    </div>
  );
}

/** Current FTP/critical running power/threshold pace, each the best qualifying effort within a
 * trailing window (default 16 weeks) - so a value can drop as an old best effort ages out, not
 * just rise. A stale value (no automatic update since its source aged out) is surfaced here with
 * a manual refresh, never silently corrected - the next uploaded activity will also refresh it. */
export function ThresholdSummaryCard() {
  const { user } = useAuth();
  const thresholdsQuery = useQuery({
    queryKey: ["thresholds", user?.id],
    queryFn: () => getThresholds(user!.id),
    enabled: !!user,
  });

  if (!thresholdsQuery.data) {
    return null;
  }

  return (
    <Card>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 20 }}>
        {FIELDS.map(({ field, label, unit }) => (
          <FieldCard key={field} field={field} label={label} unit={unit} entry={thresholdsQuery.data[field]} />
        ))}
      </div>
    </Card>
  );
}
