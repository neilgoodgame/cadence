import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ThresholdEditResult } from "../../api/types";
import { dateStatus, daysBeforeToday, previewMessageKind, todayISODate } from "./thresholdCorrection";

function result(overrides: Partial<ThresholdEditResult> = {}): ThresholdEditResult {
  return {
    changed: true,
    became_current: true,
    is_correction_only: false,
    ...overrides,
  };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-11-04T12:00:00Z"));
});

afterEach(() => {
  vi.useRealTimers();
});

describe("todayISODate/daysBeforeToday", () => {
  it("returns today's date as YYYY-MM-DD", () => {
    expect(todayISODate()).toBe("2026-11-04");
  });

  it("counts whole days before today, 0 for today itself", () => {
    expect(daysBeforeToday("2026-11-04")).toBe(0);
    expect(daysBeforeToday("2026-10-04")).toBe(31);
  });
});

describe("dateStatus", () => {
  // Issue #505: the re-rate checkbox must be hidden for today's date - isPastDate is what
  // ThresholdCorrectionModal gates that on.
  it("today's date is neither past nor future, and never outside the window", () => {
    expect(dateStatus("2026-11-04", 112)).toEqual({ isPastDate: false, isFutureDate: false, outsideWindow: false });
  });

  it("a future date is flagged, never outside the window (that only applies to the past)", () => {
    expect(dateStatus("2026-11-05", 112)).toEqual({ isPastDate: false, isFutureDate: true, outsideWindow: false });
  });

  it("a past date within the window is past but not outside it", () => {
    // 10 days before today, well within a 112-day window.
    expect(dateStatus("2026-10-25", 112)).toEqual({ isPastDate: true, isFutureDate: false, outsideWindow: false });
  });

  it("a past date beyond the window is flagged outside it", () => {
    // 200 days before today, beyond a 112-day window.
    expect(dateStatus("2026-04-18", 112)).toEqual({ isPastDate: true, isFutureDate: false, outsideWindow: true });
  });

  it("exactly at the window boundary is not yet outside it", () => {
    const windowDays = 30;
    const boundary = "2026-10-05"; // exactly 30 days before the faked 2026-11-04 "today"
    expect(daysBeforeToday(boundary)).toBe(windowDays);
    expect(dateStatus(boundary, windowDays).outsideWindow).toBe(false);
  });
});

describe("previewMessageKind", () => {
  it("is no-change when the preview says nothing changed", () => {
    expect(previewMessageKind(result({ changed: false }), false)).toBe("no-change");
    // outsideWindow must not override an unchanged result.
    expect(previewMessageKind(result({ changed: false }), true)).toBe("no-change");
  });

  it("is became-current for a real, non-correction-only change", () => {
    expect(previewMessageKind(result({ changed: true, is_correction_only: false }), false)).toBe("became-current");
  });

  it("is correction-later-row for a correction-only result within the window", () => {
    // outsideWindow=false means condition 2 held, so it must be condition 1 (a later row) that
    // kept this from becoming current.
    expect(previewMessageKind(result({ changed: true, is_correction_only: true }), false)).toBe(
      "correction-later-row",
    );
  });

  it("is correction-outside-window for a correction-only result outside the window", () => {
    expect(previewMessageKind(result({ changed: true, is_correction_only: true }), true)).toBe(
      "correction-outside-window",
    );
  });
});
