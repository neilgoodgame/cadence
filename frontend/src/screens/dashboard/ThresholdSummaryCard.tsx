import { useState } from "react";
import { useQuery, useQueryClient, useMutation } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getThresholds, listZones, refreshThreshold } from "../../api/athletes";
import { Card } from "../../components/Card";
import { formatPace, parsePace } from "../../lib/format";
import { zoneRange } from "../../lib/zones";
import { useAuth } from "../../auth/AuthContext";
import type { ThresholdFieldName, ThresholdSummaryEntry, Zone, ZoneType } from "../../api/types";

type TabField = ThresholdFieldName | "lthr";

const FIELDS: { field: TabField; label: string; referenceLabel: string; unit: string; zoneType: ZoneType }[] = [
  { field: "ftp", label: "FTP", referenceLabel: "FTP", unit: "W", zoneType: "bike_power" },
  { field: "critical_run_power", label: "Critical running power", referenceLabel: "critical power", unit: "W", zoneType: "run_power" },
  { field: "threshold_pace", label: "Threshold pace", referenceLabel: "threshold pace", unit: "", zoneType: "pace" },
  // LTHR isn't part of the rolling threshold-history ledger (ThresholdFieldName only covers the
  // other three) - a plain profile value, so this tab shows less: no delta/validity/stale/
  // history, just the value and its zones.
  { field: "lthr", label: "Heart rate", referenceLabel: "LTHR", unit: "bpm", zoneType: "heart_rate" },
];

const ZONE_COLORS = ["var(--zone-1)", "var(--zone-2)", "var(--zone-3)", "var(--zone-4)", "var(--zone-5)"];

const UNIT_BY_ZONE_TYPE: Record<ZoneType, string> = {
  heart_rate: "bpm",
  bike_power: "W",
  run_power: "W",
  pace: "/km",
};

// ThresholdSummaryEntry.value is already "M:SS" for threshold_pace (matches the backend's
// value_pace field verbatim) - not seconds, so it's displayed as-is rather than run through
// formatPace (which expects a number of seconds, not a string).
function formatValue(field: TabField, value: number | string): string {
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

// zoneRange() returns raw seconds/km for pace - formatPace() always appends " /km", so this
// trims it for compact inline ranges ("4:36–5:12 /km" rather than "4:36 /km–5:12 /km").
function formatPaceBound(seconds: number): string {
  return formatPace(seconds).replace(" /km", "");
}

function formatZoneRange(zone: Zone, reference: number, zoneType: ZoneType): string {
  const range = zoneRange(zone, reference, zoneType);
  const unit = UNIT_BY_ZONE_TYPE[zoneType];
  if (zoneType === "pace") {
    return range.high != null
      ? `${formatPaceBound(range.low)}–${formatPaceBound(range.high)} ${unit}`
      : `> ${formatPaceBound(range.low)} ${unit}`;
  }
  return range.high != null ? `${range.low}–${range.high} ${unit}` : `${range.low}+ ${unit}`;
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
 * Each field's zones (collapsed by default) read the same live ZoneSet the Preferences zone
 * editor writes, so they always match regardless of how those zones were generated. */
export function ThresholdSummaryCard() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const [activeField, setActiveField] = useState<TabField>("ftp");
  const [zonesExpanded, setZonesExpanded] = useState(false);

  const thresholdsQuery = useQuery({
    queryKey: ["thresholds", user?.id],
    queryFn: () => getThresholds(user!.id),
    enabled: !!user,
  });
  // Same queryKey ZoneEditorTab.tsx uses for its athlete-level fetch - shares that cache entry,
  // so this card can never show zones out of sync with what Preferences shows.
  const zonesQuery = useQuery({
    queryKey: ["zones", user?.id],
    queryFn: () => listZones(user!.id),
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

  const zoneSet = zonesQuery.data?.data.find((z) => z.type === active.zoneType);
  const zoneSpans = zoneSet?.zones.map((z) => z.high_pct - z.low_pct) ?? [];
  const totalZoneSpan = zoneSpans.reduce((a, b) => a + Math.max(b, 1), 0) || 1;

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
      {active.field !== "lthr" && (
        <Link to={`/thresholds/${active.field}`} style={{ display: "inline-block", marginTop: 10, fontSize: 12, color: "var(--ember)", fontWeight: 600 }}>
          View history
        </Link>
      )}

      <div
        onClick={() => setZonesExpanded((v) => !v)}
        style={{
          display: "flex", alignItems: "center", gap: 5, marginTop: 16, paddingTop: 14,
          borderTop: "1px solid var(--line)", fontSize: 12, fontWeight: 600, color: "var(--ink2)",
          cursor: "pointer", width: "fit-content",
        }}
      >
        <span style={{ display: "inline-block", fontSize: 9, transform: zonesExpanded ? "rotate(90deg)" : "none", transition: "transform 0.15s" }}>
          &#9656;
        </span>
        {zonesExpanded ? "Hide zones" : "Show zones"}
      </div>
      {zonesExpanded && (
        <div style={{ marginTop: 12 }}>
          {zoneSet && zoneSet.reference != null ? (
            <>
              <div style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 10 }}>
                as % of {active.referenceLabel} (
                {active.zoneType === "pace" ? formatPaceBound(zoneSet.reference) : zoneSet.reference}{" "}
                {UNIT_BY_ZONE_TYPE[active.zoneType]})
              </div>
              <div style={{ display: "flex", height: 8, borderRadius: 4, overflow: "hidden", marginBottom: 6 }}>
                {zoneSet.zones.map((zone, i) => (
                  <div
                    key={zone.name}
                    style={{ width: `${(Math.max(zoneSpans[i], 1) / totalZoneSpan) * 100}%`, background: ZONE_COLORS[i % ZONE_COLORS.length] }}
                  />
                ))}
              </div>
              {zoneSet.zones.map((zone, i) => (
                <div key={zone.name} style={{ display: "flex", alignItems: "center", gap: 10, padding: "7px 0", fontSize: 13, borderTop: "1px solid var(--line)" }}>
                  <span style={{ width: 8, height: 8, borderRadius: 2, flexShrink: 0, background: ZONE_COLORS[i % ZONE_COLORS.length] }} />
                  <span style={{ flex: 1, color: "var(--ink)" }}>{zone.name}</span>
                  <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>
                    {formatZoneRange(zone, zoneSet.reference!, active.zoneType)}
                  </span>
                </div>
              ))}
            </>
          ) : (
            <div style={{ fontSize: 12, color: "var(--ink3)" }}>No zones set yet.</div>
          )}
        </div>
      )}
    </Card>
  );
}
