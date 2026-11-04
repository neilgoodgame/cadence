import type { ThresholdEditResult } from "../../api/types";

export function todayISODate(): string {
  return new Date().toISOString().slice(0, 10);
}

/** Days from `isoDate` up to (not including) today - 0 for today itself, positive for a past
 * date. Plain calendar-day math (both are midnight-UTC-anchored "YYYY-MM-DD" strings), not a
 * precise elapsed-time count. */
export function daysBeforeToday(isoDate: string): number {
  const msPerDay = 24 * 60 * 60 * 1000;
  return Math.round((Date.parse(todayISODate()) - Date.parse(isoDate)) / msPerDay);
}

export interface DateStatus {
  isPastDate: boolean;
  isFutureDate: boolean;
  /** #493's condition 2, computable client-side from what ThresholdCorrectionModal already has
   * - a warning, not an error, since the edit is still allowed; it just won't become the
   * current value. */
  outsideWindow: boolean;
}

export function dateStatus(effectiveFrom: string, thresholdWindowDays: number): DateStatus {
  const today = todayISODate();
  const isPastDate = effectiveFrom < today;
  const isFutureDate = effectiveFrom > today;
  const outsideWindow = isPastDate && daysBeforeToday(effectiveFrom) > thresholdWindowDays;
  return { isPastDate, isFutureDate, outsideWindow };
}

export type PreviewMessageKind = "no-change" | "correction-outside-window" | "correction-later-row" | "became-current";

/** Which of the four preview-result messages ThresholdCorrectionModal should show. A
 * correction-only result (#493's condition 1 or condition 2 failing) is attributed to whichever
 * of the two `outsideWindow` (computed client-side by dateStatus above) already tells us -
 * condition 2 failing if it's outside the window, condition 1 (a later row exists) otherwise -
 * without the backend needing to say which one actually failed (issue #505). */
export function previewMessageKind(result: ThresholdEditResult, outsideWindow: boolean): PreviewMessageKind {
  if (!result.changed) return "no-change";
  if (!result.is_correction_only) return "became-current";
  return outsideWindow ? "correction-outside-window" : "correction-later-row";
}
