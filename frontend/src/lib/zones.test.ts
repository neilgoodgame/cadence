import { describe, expect, it } from "vitest";
import { bucketIntoZones, zoneRange } from "./zones";
import type { Zone, ZoneSet } from "../api/types";

const HR_ZONES: ZoneSet = {
  type: "heart_rate",
  reference: 158,
  zones: [
    { name: "Z1 Recovery", low_pct: 0, high_pct: 55 },
    { name: "Z2 Endurance", low_pct: 56, high_pct: 75 },
    { name: "Z3 Tempo", low_pct: 76, high_pct: 90 },
    { name: "Z4 Threshold", low_pct: 91, high_pct: 105 },
    { name: "Z5 VO2max", low_pct: 106, high_pct: 150 },
  ],
};

describe("bucketIntoZones", () => {
  it("buckets each sample by % of reference", () => {
    // 79 = 50% of 158 (Z1), 130 = 82% (Z3), 174 = 110% (Z5)
    const result = bucketIntoZones([79, 79, 130, 174], HR_ZONES);
    expect(result.find((z) => z.name === "Z1 Recovery")?.seconds).toBe(2);
    expect(result.find((z) => z.name === "Z3 Tempo")?.seconds).toBe(1);
    expect(result.find((z) => z.name === "Z5 VO2max")?.seconds).toBe(1);
  });

  it("ignores null samples", () => {
    const result = bucketIntoZones([null, 79, null], HR_ZONES);
    const total = result.reduce((sum, z) => sum + z.seconds, 0);
    expect(total).toBe(1);
  });

  it("the top zone has no upper bound", () => {
    const result = bucketIntoZones([300], HR_ZONES); // 190% of reference
    expect(result.find((z) => z.name === "Z5 VO2max")?.seconds).toBe(1);
  });
});

describe("zoneRange", () => {
  it("scales directly for non-pace types (higher % = higher value)", () => {
    const zone: Zone = { name: "Z2 Endurance", low_pct: 56, high_pct: 75 };
    expect(zoneRange(zone, 158, "heart_rate")).toEqual({ low: 88, high: 119 });
  });

  it("uses the reciprocal for pace, since a lower value is a faster/harder effort", () => {
    // threshold pace 270s/km (4:30/km): Z5 VO2max (106-150%) should be *faster* than
    // threshold, i.e. a *lower* seconds/km range, not a higher one.
    const z5: Zone = { name: "Z5 VO2max", low_pct: 106, high_pct: 150 };
    expect(zoneRange(z5, 270, "pace")).toEqual({ low: 180, high: 255 });
  });

  it("pace's easiest zone (low_pct 0) has no finite slow-end bound", () => {
    const z1: Zone = { name: "Z1 Recovery", low_pct: 0, high_pct: 55 };
    expect(zoneRange(z1, 270, "pace")).toEqual({ low: 491, high: null });
  });
});
