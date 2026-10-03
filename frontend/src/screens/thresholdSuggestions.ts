import type { CSSProperties } from "react";
import { formatPace, parsePace } from "../lib/format";
import { FIELDS } from "../lib/thresholdFields";
import type { ThresholdFieldName, ThresholdSuggestion } from "../api/types";

export const SUGGESTION_DOT_COLOR: Record<"rejected" | "upcoming_drop", string> = {
  rejected: "#f0a02e",
  upcoming_drop: "#e0442e",
};

export function fieldLabel(field: ThresholdFieldName): string {
  return FIELDS.find((f) => f.field === field)!.label;
}

// "4:07/km" for pace (converting implied_from.value's raw seconds, since that one field is
// never pre-formatted by the backend the way current/proposed are - see
// ThresholdSuggestion's own doc comment) or an already-"M:SS" string; "265W" for the two power
// fields.
export function formatFieldValue(field: ThresholdFieldName, value: number | string): string {
  if (field === "threshold_pace") {
    const mmss = typeof value === "number" ? formatPace(value).replace(" /km", "") : value;
    return `${mmss}/km`;
  }
  return `${typeof value === "number" ? Math.round(value) : value}W`;
}

function fmtDMon(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

function fmtDdDMon(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
}

/** lead = threshold_warning_days, scaled down for a trailing window under 12 weeks - mirrors
 * threshold_history.py's warning_lead_days/ThresholdHistoryCalculator.warningLeadDays exactly.
 * Not itself part of the API response (see ThresholdSuggestion's doc comment), so recomputed
 * here from the two profile settings it's derived from. */
export function warningLeadDays(thresholdWindowDays: number, thresholdWarningDays: number): number {
  return thresholdWindowDays >= 84 ? thresholdWarningDays : Math.floor(thresholdWindowDays / 4);
}

export function suggestionTag(kind: "rejected" | "upcoming_drop"): string {
  return kind === "rejected" ? "Needs review" : "Dropping soon";
}

export function suggestionTitle(s: ThresholdSuggestion): string {
  const label = fieldLabel(s.field);
  if (s.kind === "rejected") {
    return `Accept ${label} ${formatFieldValue(s.field, s.proposed!)}?`;
  }
  if (s.proposed == null) {
    return `${label} goes stale on ${fmtDMon(s.expiry_date!)}`;
  }
  const verb = s.field === "threshold_pace" ? "slips to" : "drops to";
  return `${label} ${verb} ${formatFieldValue(s.field, s.proposed)} on ${fmtDMon(s.expiry_date!)}`;
}

export function suggestionDetail(s: ThresholdSuggestion, thresholdSanityPct: number): string {
  if (s.kind === "rejected") {
    const implied = s.implied_from!;
    const impliedStr = formatFieldValue(s.field, implied.value);
    const proposedStr = formatFieldValue(s.field, s.proposed!);
    const windowLabel = implied.window.replace("min", "-min");
    const dateLabel = fmtDdDMon(s.activity_date!);
    // No separate "implies" clause when the raw effort and the applied value are the same
    // number - e.g. ftp_calculation_method=sixty_min_direct (no x0.95 step) or any field with
    // no multiplier at all (critical_run_power, threshold_pace).
    const impliesClause = impliedStr === proposedStr ? "" : ` implies ${proposedStr}`;
    const deltaClause = s.delta_pct != null ? ` (+${s.delta_pct}%)` : "";
    return `A ${windowLabel} effort of ${impliedStr} on ${dateLabel}${impliesClause}${deltaClause}. That is outside your ±${thresholdSanityPct}% sanity range, so it wasn't applied.`;
  }
  const dateLabel = fmtDMon(s.activity_date!);
  const currentStr = formatFieldValue(s.field, s.current!);
  const n = s.days_left;
  const raceClause = s.field === "ftp" ? "" : " or a 5K–10K race";
  return `The ${dateLabel} effort behind ${currentStr} ages out of your trailing window in ${n} day${n === 1 ? "" : "s"}. A hard 20–30 min test${raceClause} before then keeps it.`;
}

/** Meta line (upcoming_drop only), e.g. "Expires in 12 days · −7 s/km (2.9%) · warning lead
 * time 21 days" - the delta clause is dropped when there's no successor (proposed: null). */
export function suggestionMeta(s: ThresholdSuggestion, lead: number): string | null {
  if (s.kind !== "upcoming_drop" || s.days_left == null) return null;
  const n = s.days_left;
  let deltaClause = "";
  if (s.proposed != null && s.current != null && s.delta_pct != null) {
    const unit = s.field === "threshold_pace" ? "s/km" : "W";
    const currentNum = s.field === "threshold_pace" ? parsePace(String(s.current)) : Number(s.current);
    const proposedNum = s.field === "threshold_pace" ? parsePace(String(s.proposed)) : Number(s.proposed);
    if (currentNum != null && proposedNum != null) {
      const absDelta = Math.round(Math.abs(proposedNum - currentNum));
      deltaClause = ` · −${absDelta} ${unit} (${s.delta_pct}%)`;
    }
  }
  return `Expires in ${n} day${n === 1 ? "" : "s"}${deltaClause} · warning lead time ${lead} days`;
}

export function raceWillRefreshNote(s: ThresholdSuggestion): string {
  return `Your ${s.race!.name} on ${fmtDMon(s.race!.date)} should refresh this before it expires on ${fmtDMon(s.expiry_date!)}.`;
}

export function acceptedDoneText(field: ThresholdFieldName, value: number | string, effectiveFrom: string): string {
  return `${fieldLabel(field)} set to ${formatFieldValue(field, value)} — recorded from ${fmtDMon(effectiveFrom)}.`;
}

export type ActionableSuggestion = ThresholdSuggestion & { kind: "rejected" | "upcoming_drop" };

/** Eligible for the Dashboard banner: every "rejected", but an "upcoming_drop" only once it's
 * within 10 days of expiry (further-out ones are listed on Thresholds & zones only).
 * race_will_refresh never shows on the Dashboard - it's a quiet note on Current zones instead.
 * A type predicate so callers (pickBannerSuggestion below) get a narrowed, never-race_will_
 * refresh type back without an extra cast. */
export function bannerEligible(s: ThresholdSuggestion): s is ActionableSuggestion {
  if (s.kind === "race_will_refresh") return false;
  if (s.kind === "rejected") return true;
  return s.days_left != null && s.days_left <= 10;
}

/** Rejected first, then upcoming_drop/race_will_refresh by days_left ascending - matches the
 * backend's own list_suggestions ordering (threshold_suggestions.py::_build), re-applied here
 * so a locally-filtered/re-ordered subset (e.g. the banner's eligible-only view) stays
 * consistent with it. */
export function sortSuggestions<T extends ThresholdSuggestion>(list: T[]): T[] {
  return [...list].sort((a, b) => {
    if (a.kind === "rejected" && b.kind !== "rejected") return -1;
    if (b.kind === "rejected" && a.kind !== "rejected") return 1;
    return (a.days_left ?? 0) - (b.days_left ?? 0);
  });
}

export interface BannerPick {
  current: ActionableSuggestion;
  moreCount: number;
  moreField: ThresholdFieldName;
}

/** Picks the Dashboard banner's single suggestion - the first eligible one, in the shared sort
 * order - plus how many *other* pending suggestions exist (any eligibility, matching the
 * design reference's own buildThrSug) and which field the "+N more" link should point at. */
export function pickBannerSuggestion(all: ThresholdSuggestion[]): BannerPick | null {
  const sorted = sortSuggestions(all);
  const current = sorted.find(bannerEligible);
  if (!current) return null;
  const others = sorted.filter((s) => s.id !== current.id);
  return { current, moreCount: others.length, moreField: others[0]?.field ?? current.field };
}

export const SUGGESTION_PRIMARY_BTN_STYLE: CSSProperties = {
  padding: "6px 12px",
  borderRadius: 7,
  background: "var(--ember)",
  color: "#0b0f14",
  fontSize: 12,
  fontWeight: 700,
  border: "none",
  cursor: "pointer",
  whiteSpace: "nowrap",
  textDecoration: "none",
  display: "inline-block",
};

export const SUGGESTION_GHOST_BTN_STYLE: CSSProperties = {
  padding: "6px 12px",
  borderRadius: 7,
  border: "1px solid var(--line)",
  color: "var(--ink2)",
  fontSize: 12,
  fontWeight: 600,
  background: "transparent",
  cursor: "pointer",
  whiteSpace: "nowrap",
  textDecoration: "none",
  display: "inline-block",
};

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export function todayISODate(): string {
  return isoDate(new Date());
}

export function tomorrowISODate(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  return isoDate(d);
}
