import { describe, expect, it } from "vitest";
import { defaultPhaseWeeks, matchDistanceBucket, weeksUntilRace } from "./raceDistanceBuckets";

function isoDaysFromNow(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
}

describe("matchDistanceBucket", () => {
  it("matches exact standard distances", () => {
    expect(matchDistanceBucket(5)).toBe("5k");
    expect(matchDistanceBucket(10)).toBe("10k");
    expect(matchDistanceBucket(21.0975)).toBe("half");
    expect(matchDistanceBucket(42.195)).toBe("marathon");
  });

  it("matches within the 2% tolerance", () => {
    expect(matchDistanceBucket(42.4)).toBe("marathon");
  });

  it("falls back to ultra for anything past marathon distance", () => {
    expect(matchDistanceBucket(50)).toBe("ultra");
    expect(matchDistanceBucket(100)).toBe("ultra");
  });

  it("returns null for no distance", () => {
    expect(matchDistanceBucket(null)).toBeNull();
    expect(matchDistanceBucket(undefined)).toBeNull();
    expect(matchDistanceBucket(0)).toBeNull();
  });

  it("returns null for a distance that matches no bucket", () => {
    expect(matchDistanceBucket(13)).toBeNull();
  });
});

describe("defaultPhaseWeeks", () => {
  it("sums to exactly the block length", () => {
    for (const weeks of [8, 12, 16, 20, 24]) {
      const { base, build, peak, taper } = defaultPhaseWeeks(weeks);
      expect(base + build + peak + taper).toBe(weeks);
    }
  });

  it("matches the backend's 16-week marathon split", () => {
    expect(defaultPhaseWeeks(16)).toEqual({ base: 6, build: 6, peak: 2, taper: 2 });
  });

  it("always gives peak at least one week", () => {
    expect(defaultPhaseWeeks(4).peak).toBeGreaterThanOrEqual(1);
  });
});

describe("weeksUntilRace", () => {
  it("returns null for a race today or in the past", () => {
    expect(weeksUntilRace(isoDaysFromNow(0))).toBeNull();
    expect(weeksUntilRace(isoDaysFromNow(-10))).toBeNull();
  });

  it("counts whole weeks between this week and the race's week, inclusive", () => {
    // 10 weeks out should always read as (at least) 10 - the exact day-of-week offset within
    // the current week can push it to 11 depending on what day "today" falls on, but never less.
    const weeks = weeksUntilRace(isoDaysFromNow(70));
    expect(weeks).not.toBeNull();
    expect(weeks).toBeGreaterThanOrEqual(10);
    expect(weeks).toBeLessThanOrEqual(11);
  });

  it("a race later this same week still counts as 1 week remaining", () => {
    // Tomorrow is always in "this week or next" - never more than 1 week of calendar gap.
    const weeks = weeksUntilRace(isoDaysFromNow(1));
    expect(weeks).toBeGreaterThanOrEqual(1);
    expect(weeks).toBeLessThanOrEqual(2);
  });
});
