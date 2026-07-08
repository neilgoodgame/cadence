import { describe, expect, it } from "vitest";
import { generateHrZones, hrZoneBandsToZones, validateHrZoneParams } from "./hrZones";

// The example from generate_hr_zones.py's docstring.
const PARAMS = { restingHr: 45, lthr: 155, maxHr: 176 };

function ranges(bands: ReturnType<typeof generateHrZones>): [number, number][] {
  return bands.map((b) => [b.minBpm, b.maxBpm]);
}

describe("generateHrZones", () => {
  it("computes coggan zones anchored to LTHR, capped at max HR", () => {
    expect(ranges(generateHrZones("coggan", PARAMS))).toEqual([
      [0, 104],
      [105, 128],
      [129, 145],
      [146, 162],
      [163, 176],
    ]);
  });

  it("computes max-hr zones as 50-100% of max", () => {
    expect(ranges(generateHrZones("max_hr", PARAMS))).toEqual([
      [88, 105],
      [106, 122],
      [123, 140],
      [141, 157],
      [158, 176],
    ]);
  });

  it("computes karvonen zones as 50-100% of heart rate reserve", () => {
    expect(ranges(generateHrZones("karvonen", PARAMS))).toEqual([
      [111, 123],
      [124, 136],
      [137, 149],
      [150, 162],
      [163, 176],
    ]);
  });

  it("produces contiguous zones with no gaps or overlaps", () => {
    for (const method of ["max_hr", "karvonen", "coggan"] as const) {
      const bands = generateHrZones(method, PARAMS);
      for (let i = 1; i < bands.length; i++) {
        expect(bands[i].minBpm).toBe(bands[i - 1].maxBpm + 1);
      }
      expect(bands.at(-1)!.maxBpm).toBe(PARAMS.maxHr);
    }
  });
});

describe("validateHrZoneParams", () => {
  it("accepts the reference example for every method", () => {
    expect(validateHrZoneParams("coggan", PARAMS)).toBeNull();
    expect(validateHrZoneParams("max_hr", PARAMS)).toBeNull();
    expect(validateHrZoneParams("karvonen", PARAMS)).toBeNull();
  });

  it("rejects resting HR at or above LTHR", () => {
    expect(validateHrZoneParams("coggan", { ...PARAMS, restingHr: 155 })).toMatch(/Resting HR/);
  });

  it("rejects LTHR at or above max HR", () => {
    expect(validateHrZoneParams("coggan", { ...PARAMS, lthr: 176 })).toMatch(/LTHR/);
  });

  it("requires resting HR only for karvonen", () => {
    expect(validateHrZoneParams("karvonen", { ...PARAMS, restingHr: null })).toMatch(/resting/i);
    expect(validateHrZoneParams("coggan", { ...PARAMS, restingHr: null })).toBeNull();
  });
});

describe("hrZoneBandsToZones", () => {
  it("converts bpm bands to %-of-LTHR zones for the API", () => {
    const zones = hrZoneBandsToZones(generateHrZones("coggan", PARAMS), PARAMS.lthr);
    expect(zones[0]).toEqual({ name: "Z1 Active Recovery", low_pct: 0, high_pct: 67 });
    expect(zones[4]).toEqual({ name: "Z5 VO2max", low_pct: 105, high_pct: 114 });
  });
});
