import { describe, expect, it } from "vitest";
import { bucketIntoZones } from "./zones";
import type { ZoneSet } from "../api/types";

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
