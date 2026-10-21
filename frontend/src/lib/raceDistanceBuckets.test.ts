import { describe, expect, it } from "vitest";
import { defaultPhaseWeeks, matchDistanceBucket } from "./raceDistanceBuckets";

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
