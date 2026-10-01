import { Fragment } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getThresholdHistory, getThresholds, listZones } from "../api/athletes";
import { useAuth } from "../auth/AuthContext";
import { Card } from "../components/Card";
import { ActivityNameLink } from "../components/ActivityNameLink";
import { ThresholdHistoryChart } from "./ThresholdHistoryChart";
import { daysInEffect, deltaLabel } from "./thresholdHistory";
import { FIELDS, formatValue, formatZoneRange, type TabField } from "../lib/thresholdFields";
import { parsePace } from "../lib/format";
import type { ThresholdFieldName } from "../api/types";

const ZONE_COLORS = ["var(--zone-1)", "var(--zone-2)", "var(--zone-3)", "var(--zone-4)", "var(--zone-5)"];

// entry.value is already "M:SS" for threshold_pace (matches the backend's value_pace field
// verbatim) - not seconds, so it's displayed as-is rather than run through formatPace (which
// expects a number of seconds, not a string - Number("4:30") is NaN).
function formatLedgerValue(field: ThresholdFieldName, value: number | string): string {
  return field === "threshold_pace" ? `${value}/km` : `${value}W`;
}

function fmtShortDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

// "Before {d Mon}" - the same date as the big "Effective from" line above, just without the
// year (the 4th column header sits right next to the ledger's own dated rows, which already
// carry the year).
function fmtShortDateNoYear(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

const GRID_COLS = "28px minmax(90px,0.8fr) minmax(160px,1.4fr) minmax(115px,0.95fr) minmax(105px,0.85fr) minmax(140px,1.1fr) minmax(115px,0.95fr)";

const fieldTabStyle = (active: boolean): React.CSSProperties => ({
  padding: "6px 12px",
  borderRadius: 7,
  fontSize: 12,
  fontWeight: 600,
  cursor: "pointer",
  whiteSpace: "nowrap",
  color: active ? "#fff" : "var(--ink3)",
  background: active ? "var(--ember)" : "none",
});

// Parses a field's current/display value down to the plain number zoneRange() needs - a string
// "M:SS" for pace, already-numeric for everything else (including LTHR, a plain number).
function numericReference(field: TabField, value: number | string): number | null {
  return field === "threshold_pace" ? parsePace(String(value)) : Number(value);
}

/** "Thresholds & zones": the full ledger for one field, plus (new, Oct 2026 handoff) a Current
 * zones card comparing each zone's range now against its range under the previous threshold.
 * Reachable only via the dashboard's ThresholdSummaryCard links (not in the sidebar nav),
 * matching /activities/:id's own direct-link-only pattern. The ledger itself is laid out as a
 * wide table (matching BestEffortsScreen's own CardShell/ColHeaders/grid-row convention) rather
 * than a narrow row list, so its full detail - which activity, the delta vs the previous entry,
 * whether a later ingest revealed this entry after the fact - is visible without leaving the
 * page. LTHR has no ledger or chart (a plain profile value, not part of the rolling
 * threshold-history mechanic) - just the zones card and a note. */
export function ThresholdHistoryScreen() {
  const { field: rawField } = useParams<{ field: string }>();
  const { user } = useAuth();

  const field = FIELDS.find((f) => f.field === rawField)?.field;

  const thresholdsQuery = useQuery({
    queryKey: ["thresholds", user?.id],
    queryFn: () => getThresholds(user!.id),
    enabled: !!user,
  });
  // Same queryKey ZoneEditorTab.tsx/ThresholdSummaryCard.tsx use for their own athlete-level
  // fetch - shares that cache entry, so this card can never show zones out of sync with what
  // Preferences or the dashboard shows.
  const zonesQuery = useQuery({
    queryKey: ["zones", user?.id],
    queryFn: () => listZones(user!.id),
    enabled: !!user,
  });
  const historyQuery = useQuery({
    queryKey: ["threshold-history", user?.id, field],
    queryFn: () => getThresholdHistory(user!.id, field as ThresholdFieldName),
    // LTHR isn't a ThresholdFieldName - there's no ledger to fetch for it at all.
    enabled: !!user && !!field && field !== "lthr",
  });

  if (!field) {
    return <Navigate to="/thresholds/ftp" replace />;
  }
  if (!user) {
    return null;
  }

  const active = FIELDS.find((f) => f.field === field)!;
  const entries = historyQuery.data?.data ?? [];
  const days = daysInEffect(entries);
  const maxDays = Math.max(1, ...days);

  const currentValue = active.field === "lthr" ? user.lthr : thresholdsQuery.data?.[active.field as ThresholdFieldName]?.value ?? null;
  const currentSince =
    active.field === "lthr" ? "Set manually in your profile" : entries[0] ? `Effective from ${fmtShortDate(entries[0].current_from)}` : null;

  const zoneSet = zonesQuery.data?.data.find((z) => z.type === active.zoneType);
  const zoneSpans = zoneSet?.zones.map((z) => z.high_pct - z.low_pct) ?? [];
  const totalZoneSpan = zoneSpans.reduce((a, b) => a + Math.max(b, 1), 0) || 1;

  const referenceNow = currentValue != null ? numericReference(active.field, currentValue) : null;
  const hasPrevious = active.field !== "lthr" && entries.length > 1;
  const referenceBefore = hasPrevious ? numericReference(active.field, entries[1].value) : null;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <div style={{ maxWidth: 1200 }}>
        <Link to="/" style={{ fontSize: 13, color: "var(--ink3)" }}>
          &larr; Back to dashboard
        </Link>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, flexWrap: "wrap", marginTop: 8 }}>
          <h1 style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.02em", margin: 0, color: "var(--ink)" }}>Thresholds &amp; zones</h1>
          <div style={{ display: "flex", gap: 3, background: "var(--canvas)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
            {FIELDS.map((f) => (
              <Link key={f.field} to={`/thresholds/${f.field}`} style={{ textDecoration: "none" }}>
                <div style={fieldTabStyle(f.field === active.field)}>{f.label}</div>
              </Link>
            ))}
          </div>
        </div>
      </div>

      <Card style={{ padding: "20px 24px" }}>
        <div style={{ display: "flex", alignItems: "flex-end", justifyContent: "space-between", gap: 16, flexWrap: "wrap", marginBottom: 16 }}>
          <div>
            <div className="mono" style={{ fontSize: 11, letterSpacing: "0.08em", color: "var(--ink3)", textTransform: "uppercase" }}>
              Current {active.referenceLabel}
            </div>
            <div style={{ display: "flex", alignItems: "baseline", gap: 8, marginTop: 4 }}>
              <span className="mono" style={{ fontSize: 32, fontWeight: 600, color: "var(--ink)" }}>
                {currentValue != null ? formatValue(active.field, currentValue) : "—"}
              </span>
              {active.unit && currentValue != null && <span style={{ fontSize: 14, fontWeight: 500, color: "var(--ink2)" }}>{active.unit}</span>}
            </div>
            {currentSince && <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>{currentSince}</div>}
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
            <span style={{ fontSize: 12, color: "var(--ink3)" }}>Zones as % of {active.referenceLabel}</span>
            <Link to="/preferences" style={{ fontSize: 12, fontWeight: 600, color: "var(--ember)" }}>
              Edit zones →
            </Link>
          </div>
        </div>

        {zoneSet && zoneSet.reference != null && referenceNow != null ? (
          <>
            <div style={{ display: "flex", height: 10, borderRadius: 5, overflow: "hidden", marginBottom: 14 }}>
              {zoneSet.zones.map((zone, i) => (
                <div
                  key={zone.name}
                  style={{ width: `${(Math.max(zoneSpans[i], 1) / totalZoneSpan) * 100}%`, background: ZONE_COLORS[i % ZONE_COLORS.length] }}
                />
              ))}
            </div>
            <div
              style={{
                display: "grid",
                gridTemplateColumns: "minmax(0,1.4fr) 70px minmax(0,1fr) minmax(0,1fr)",
                columnGap: 16,
                fontSize: 13,
              }}
            >
              <div className="mono" style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", paddingBottom: 8 }}>
                Zone
              </div>
              <div className="mono" style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", paddingBottom: 8 }}>
                % thr
              </div>
              <div className="mono" style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", paddingBottom: 8 }}>
                Now
              </div>
              <div className="mono" style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", paddingBottom: 8 }}>
                {hasPrevious && entries[0] ? `Before ${fmtShortDateNoYear(entries[0].current_from)}` : ""}
              </div>

              {zoneSet.zones.map((zone, i) => {
                const isLastZone = i === zoneSet.zones.length - 1;
                const pctLabel =
                  zone.low_pct <= 0 ? `≤ ${zone.high_pct}%` : isLastZone ? `≥ ${zone.low_pct}%` : `${zone.low_pct}–${zone.high_pct}%`;
                return (
                  <Fragment key={zone.name}>
                    <div
                      style={{ display: "flex", alignItems: "center", gap: 10, padding: "9px 0", borderTop: "1px solid var(--line)", minWidth: 0 }}
                    >
                      <span style={{ width: 10, height: 10, borderRadius: 3, flexShrink: 0, background: ZONE_COLORS[i % ZONE_COLORS.length] }} />
                      <span style={{ color: "var(--ink)", fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                        {zone.name}
                      </span>
                    </div>
                    <div className="mono" style={{ padding: "9px 0", borderTop: "1px solid var(--line)", fontSize: 12, color: "var(--ink3)" }}>
                      {pctLabel}
                    </div>
                    <div className="mono" style={{ padding: "9px 0", borderTop: "1px solid var(--line)", fontSize: 13, fontWeight: 600, color: "var(--ink)" }}>
                      {formatZoneRange(zone, referenceNow, active.zoneType)}
                    </div>
                    <div className="mono" style={{ padding: "9px 0", borderTop: "1px solid var(--line)", fontSize: 12, color: "var(--ink3)" }}>
                      {hasPrevious && referenceBefore != null ? formatZoneRange(zone, referenceBefore, active.zoneType) : ""}
                    </div>
                  </Fragment>
                );
              })}
            </div>
          </>
        ) : (
          <div style={{ fontSize: 12, color: "var(--ink3)" }}>No zones set yet.</div>
        )}
      </Card>

      {active.field === "lthr" ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>LTHR is set manually in Preferences — no history is recorded.</div>
      ) : (
        <>
          {historyQuery.isLoading && <div style={{ color: "var(--ink3)" }}>Loading…</div>}

          {!historyQuery.isLoading && entries.length === 0 && (
            <div style={{ color: "var(--ink3)", fontSize: 13, maxWidth: 560 }}>
              No history yet - this fills in as qualifying activities are uploaded, or via a bulk
              rebuild in Preferences &rsaquo; Best efforts.
            </div>
          )}

          {entries.length > 0 && (
            <>
              <div style={{ fontSize: 16, fontWeight: 700, letterSpacing: "-0.01em", color: "var(--ink)", marginTop: 4 }}>History</div>

              <Card style={{ padding: 0 }}>
                <ThresholdHistoryChart field={active.field as ThresholdFieldName} points={entries} />
              </Card>

              <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, overflow: "hidden" }}>
                <div
                  style={{
                    display: "flex", alignItems: "center", justifyContent: "space-between",
                    padding: "14px 18px", borderBottom: "1px solid var(--line)",
                  }}
                >
                  <span className="mono" style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", color: "var(--ink3)" }}>
                    {active.label.toUpperCase()} &middot; LEDGER
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
                  // effective_from is the qualifying activity's own date; current_from is when
                  // this row actually became the recorded current value (see
                  // ThresholdHistoryPoint) - they differ exactly when a later ingest revealed an
                  // earlier, dormant effort (the same "revealed" case ThresholdHistoryIndicator
                  // surfaces on the activity page).
                  const revealed = entry.effective_from !== entry.current_from;
                  const delta = i < entries.length - 1 ? deltaLabel(active.field as ThresholdFieldName, entry.value, entries[i + 1].value) : null;
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
                        {formatLedgerValue(active.field as ThresholdFieldName, entry.value)}
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
            </>
          )}
        </>
      )}
    </div>
  );
}
