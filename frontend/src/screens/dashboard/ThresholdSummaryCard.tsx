import { useState } from "react";
import { useQuery, useQueryClient, useMutation } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getThresholds, refreshThreshold } from "../../api/athletes";
import { Card } from "../../components/Card";
import { formatPace, parsePace } from "../../lib/format";
import { FIELDS, formatValue, type TabField } from "../../lib/thresholdFields";
import { useAuth } from "../../auth/AuthContext";
import type { ThresholdFieldName, ThresholdSummaryEntry } from "../../api/types";

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
function deltaLabel(field: TabField, entry: ThresholdSummaryEntry): string | null {
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

const tabStyle = (isActive: boolean): React.CSSProperties => ({
  padding: "7px 14px",
  borderRadius: 7,
  fontSize: 13,
  fontWeight: 600,
  cursor: "pointer",
  whiteSpace: "nowrap",
  color: isActive ? "var(--ink)" : "var(--ink3)",
  background: isActive ? "var(--card)" : "transparent",
  boxShadow: isActive ? "0 1px 2px rgba(0,0,0,0.14)" : "none",
});

/** Current FTP/critical running power/threshold pace/LTHR, one at a time via tabs (each is the
 * best qualifying effort within a trailing window, default 16 weeks - so a value can drop as an
 * old best effort ages out, not just rise - except LTHR, a plain profile value with no such
 * mechanic). A stale value (no automatic update since its source aged out) is surfaced with a
 * manual refresh, never silently corrected - the next uploaded activity will also refresh it.
 * Zones live one level down now, on /thresholds/:field (ThresholdHistoryScreen's "Current
 * zones" card) - every tab, LTHR included, links there via "Zones & history →". */
export function ThresholdSummaryCard() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const [activeField, setActiveField] = useState<TabField>("ftp");

  const thresholdsQuery = useQuery({
    queryKey: ["thresholds", user?.id],
    queryFn: () => getThresholds(user!.id),
    enabled: !!user,
  });
  const refreshMutation = useMutation({
    mutationFn: (field: ThresholdFieldName) => refreshThreshold(user!.id, field),
    onSuccess: (updated) => qc.setQueryData(["thresholds", user!.id], updated),
  });

  if (!user || !thresholdsQuery.data) {
    return null;
  }

  const active = FIELDS.find((f) => f.field === activeField)!;
  const entry: ThresholdSummaryEntry | null =
    active.field === "lthr" ? null : thresholdsQuery.data[active.field as ThresholdFieldName];

  const value = active.field === "lthr" ? user.lthr : entry!.value;
  const delta = entry ? deltaLabel(active.field, entry) : null;
  const remaining =
    entry && !entry.stale && entry.value != null && entry.effective_from
      ? daysRemaining(entry.effective_from, user.threshold_window_days)
      : null;
  const subtitle = active.field === "lthr" ? "Set manually in your profile" : "Best qualifying effort within your trailing window";

  return (
    <Card>
      <div style={{ fontSize: 16, fontWeight: 700, letterSpacing: "-0.01em", color: "var(--ink)" }}>Thresholds</div>
      <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>{subtitle}</div>

      <div
        style={{
          display: "flex", gap: 3, background: "var(--canvas)", border: "1px solid var(--line)",
          borderRadius: 10, padding: 3, margin: "16px 0 20px", width: "fit-content",
        }}
      >
        {FIELDS.map((f) => (
          <div key={f.field} onClick={() => setActiveField(f.field)} style={tabStyle(f.field === active.field)}>
            {f.label}
          </div>
        ))}
      </div>

      <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
        <span className="mono" style={{ fontSize: 32, fontWeight: 600, color: "var(--ink)" }}>
          {value != null ? formatValue(active.field, value) : "—"}
        </span>
        {active.unit && value != null && <span style={{ fontSize: 14, fontWeight: 500, color: "var(--ink2)" }}>{active.unit}</span>}
      </div>
      {delta && <div style={{ fontSize: 13, color: "var(--ink3)", marginTop: 4 }}>{delta}</div>}
      {remaining != null && (
        <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>
          Valid for {remaining} more day{remaining === 1 ? "" : "s"}
        </div>
      )}
      {entry?.stale && (
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginTop: 6 }}>
          <span style={{ fontSize: 12, color: "var(--ember)" }}>
            {entry.value == null ? "No qualifying effort yet" : "Aged out of window"}
          </span>
          <button
            onClick={() => refreshMutation.mutate(active.field as ThresholdFieldName)}
            disabled={refreshMutation.isPending}
            style={{
              fontSize: 12, fontWeight: 600, padding: "3px 10px", borderRadius: 6,
              border: "1px solid var(--line)", background: "transparent", color: "var(--ink2)",
              cursor: refreshMutation.isPending ? "wait" : "pointer",
            }}
          >
            {refreshMutation.isPending ? "Refreshing…" : "Refresh"}
          </button>
        </div>
      )}
      <Link
        to={`/thresholds/${active.field}`}
        style={{ display: "block", marginTop: 14, paddingTop: 12, borderTop: "1px solid var(--line)", fontSize: 12, fontWeight: 600, color: "var(--ember)" }}
      >
        Zones &amp; history →
      </Link>
    </Card>
  );
}
