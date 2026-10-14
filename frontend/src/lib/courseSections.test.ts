import { describe, expect, it } from "vitest";
import type { CourseSection } from "../api/types";
import { DEFAULT_SECTION_SETTINGS, formatGrade, profileSlice, settingsError, summarizeSections } from "./courseSections";

function section(type: CourseSection["type"], length_m: number): CourseSection {
  return { type, start_km: 0, end_km: length_m / 1000, length_m, gain_m: 0, loss_m: 0, avg_grade_pct: 0, steepest_100m_pct: 0 };
}

describe("settingsError", () => {
  it("accepts the defaults", () => {
    expect(settingsError(DEFAULT_SECTION_SETTINGS)).toBeNull();
  });

  it("names the first out-of-range field", () => {
    expect(settingsError({ ...DEFAULT_SECTION_SETTINGS, fit_tolerance_m: 0.1 })).toBe("Fit tolerance must be between 0.5 and 10 m.");
  });

  it("checks steep against flat", () => {
    expect(settingsError({ ...DEFAULT_SECTION_SETTINGS, flat_max_pct: 3, steep_min_pct: 2.5 })).toBe(
      "Steep threshold must be greater than the flat band.",
    );
  });

  it("rejects a cleared field", () => {
    expect(settingsError({ ...DEFAULT_SECTION_SETTINGS, min_section_m: Number.NaN })).not.toBeNull();
  });
});

describe("summarizeSections", () => {
  it("totals each kind and counts climbs of both steepness", () => {
    const summary = summarizeSections([
      section("flat", 3000),
      section("climb", 500),
      section("steep_climb", 200),
      section("descent", 400),
      section("steep_descent", 100),
    ]);
    expect(summary).toEqual({ flatKm: 3, climbingKm: 0.7, descendingKm: 0.5, climbs: 2 });
  });
});

describe("profileSlice", () => {
  const profile: [number, number][] = [
    [0, 10],
    [1, 20],
    [2, 10],
  ];

  it("interpolates both ends so neighbouring fills meet", () => {
    expect(profileSlice(profile, 0.5, 1.5)).toEqual([
      [0.5, 15],
      [1, 20],
      [1.5, 15],
    ]);
  });

  it("clamps to the profile's ends", () => {
    expect(profileSlice(profile, 0, 2)).toEqual(profile);
  });
});

describe("formatGrade", () => {
  it("signs climbs and descents", () => {
    expect(formatGrade(2.84)).toBe("+2.8%");
    expect(formatGrade(-4.7)).toBe("−4.7%");
    expect(formatGrade(0)).toBe("0.0%");
  });
});
