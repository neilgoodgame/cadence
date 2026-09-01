import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ThresholdHistoryPoint } from "../api/types";
import { daysInEffect, deltaLabel } from "./thresholdHistory";

function point(overrides: Partial<ThresholdHistoryPoint> = {}): ThresholdHistoryPoint {
  return {
    value: 250,
    source_activity_id: "act_x",
    effective_from: "2026-01-01",
    current_from: "2026-01-01",
    ...overrides,
  };
}

describe("daysInEffect", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-25T12:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("uses today as the end date for the most recent (first) entry", () => {
    const entries = [point({ current_from: "2026-04-20" })];
    expect(daysInEffect(entries)).toEqual([5]);
  });

  it("uses the next-more-recent entry's current_from as the end date for older entries", () => {
    // Most-recent-first, matching the API's own order.
    const entries = [point({ current_from: "2026-04-20" }), point({ current_from: "2026-01-01" })];
    // entries[1] ran from 2026-01-01 to entries[0]'s 2026-04-20 = 109 days.
    expect(daysInEffect(entries)).toEqual([5, 109]);
  });

  it("matches the real cascading-expiry scenario - a dormant entry only starts counting once revealed", () => {
    const worse = point({ current_from: "2023-12-22" }); // revealed later than its own effective_from
    const better = point({ current_from: "2023-08-26" });
    const entries = [worse, better];
    // better ran 2023-08-26 -> 2023-12-22 (worse's current_from) = 118 days.
    expect(daysInEffect(entries)[1]).toBe(118);
  });
});

describe("deltaLabel", () => {
  it("shows a signed watt delta for power fields, higher is an improvement", () => {
    expect(deltaLabel("ftp", 258, 250)).toBe("+8W vs previous");
    expect(deltaLabel("critical_run_power", 250, 258)).toBe("-8W vs previous");
  });

  it("shows a pace delta for threshold_pace, lower (faster) is an improvement", () => {
    expect(deltaLabel("threshold_pace", "4:32", "4:35")).toBe("▼ 0:03 /km vs previous");
    expect(deltaLabel("threshold_pace", "4:38", "4:35")).toBe("▲ 0:03 /km vs previous");
  });

  it("returns null when the value hasn't changed", () => {
    expect(deltaLabel("ftp", 250, 250)).toBeNull();
    expect(deltaLabel("threshold_pace", "4:35", "4:35")).toBeNull();
  });
});
