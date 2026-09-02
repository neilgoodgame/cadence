import { describe, expect, it } from "vitest";
import type { LeafStep } from "../../api/types";
import type { Leaf } from "./workoutTree";
import { buildTcx, buildZwo, type ExportThresholds } from "./workoutExport";

function leaf(overrides: Partial<LeafStep> = {}): Leaf {
  return {
    id: "s1",
    kind: "block",
    end_type: "time",
    duration: 300,
    distance: null,
    target_type: "power",
    target_low: 100,
    target_high: 100,
    power_unit: "pct_ftp",
    target2_type: "none",
    target2_low: null,
    target2_high: null,
    note: "",
    ...overrides,
  };
}

const emptyThresholds: ExportThresholds = { ftp: null, criticalRunPower: null, lthr: null, thresholdPaceSecPerKm: null };

describe("buildTcx power_unit branching", () => {
  it("emits a watts-unit step's stored value directly, ignoring any FTP reference", () => {
    const xml = buildTcx("W", "bike", [leaf({ power_unit: "watts", target_low: 250, target_high: 280 })], { ...emptyThresholds, ftp: 400 });
    expect(xml).toContain("<Value>250</Value>");
    expect(xml).toContain("<Value>280</Value>");
    expect(xml).not.toContain("1000"); // sanity: not accidentally scaled by the unrelated ftp=400
  });

  it("converts a pct_ftp-unit step to watts using the real per-sport reference", () => {
    const xml = buildTcx("P", "bike", [leaf({ power_unit: "pct_ftp", target_low: 90, target_high: 100 })], { ...emptyThresholds, ftp: 250 });
    expect(xml).toContain("<Value>225</Value>");
    expect(xml).toContain("<Value>250</Value>");
  });

  it("a pct_ftp-unit step respects an explicit override over the real reference", () => {
    const xml = buildTcx("P", "bike", [leaf({ power_unit: "pct_ftp", target_low: 100, target_high: 100 })], { ...emptyThresholds, ftp: 250 }, 300);
    expect(xml).toContain("<Value>300</Value>");
  });

  it("is sport-aware: run power uses critical_run_power, not bike ftp", () => {
    const xml = buildTcx("R", "run", [leaf({ power_unit: "pct_ftp", target_low: 100, target_high: 100 })], { ...emptyThresholds, ftp: 999, criticalRunPower: 280 });
    expect(xml).toContain("<Value>280</Value>");
    expect(xml).not.toContain("<Value>999</Value>");
  });
});

describe("buildTcx duration types", () => {
  // Regression coverage for a real bug found live: a distance-ended step (e.g. "4km at
  // 80-85% CP") exported as Time_t with 0 seconds, since the step's own raw `duration` field is
  // null for a distance-ended step and there was no branch reading `distance`/`end_type` at
  // all - Garmin showed 0:00 and no distance for every interval. TCX has a real Distance_t
  // duration the device tracks natively, so it's used directly instead of any estimate.
  it("emits a real Distance_t duration for a distance-ended step, not a zero-second Time_t", () => {
    const xml = buildTcx("D", "run", [leaf({ end_type: "distance", duration: null, distance: 4000 })]);
    expect(xml).toContain('<Duration xsi:type="Distance_t"><Meters>4000</Meters></Duration>');
    expect(xml).not.toContain("Time_t");
  });

  it("still emits Time_t for a time-ended step", () => {
    const xml = buildTcx("T", "run", [leaf({ end_type: "time", duration: 300 })]);
    expect(xml).toContain('<Duration xsi:type="Time_t"><Seconds>300</Seconds></Duration>');
  });

  it("emits UserInitiated_t for a manual (lap-press) step", () => {
    const xml = buildTcx("M", "run", [leaf({ end_type: "manual", duration: null })]);
    expect(xml).toContain('<Duration xsi:type="UserInitiated_t"/>');
  });
});

describe("buildZwo power_unit branching", () => {
  it("passes a pct_ftp-unit step's percentage straight through by default", () => {
    const xml = buildZwo("P", "bike", [leaf({ power_unit: "pct_ftp", target_low: 90, target_high: 90 })], { ...emptyThresholds, ftp: 250 });
    expect(xml).toContain('PowerLow="0.90"');
  });

  it("converts a watts-unit step to a fraction using the real reference", () => {
    const xml = buildZwo("W", "bike", [leaf({ power_unit: "watts", target_low: 200, target_high: 250 })], { ...emptyThresholds, ftp: 250 });
    expect(xml).toContain('PowerLow="0.80"');
    expect(xml).toContain('PowerHigh="1.00"');
  });

  it("a watts-unit step uses the override over the real reference when both are set", () => {
    const xml = buildZwo("W", "bike", [leaf({ power_unit: "watts", target_low: 225, target_high: 225 })], { ...emptyThresholds, ftp: 250 }, 300);
    expect(xml).toContain('PowerLow="0.75"');
  });

  it("re-bases a pct_ftp-unit step onto the override via the real reference", () => {
    // 90% of a real FTP of 250 = 225W; re-expressed against an override of 300 -> 0.75
    const xml = buildZwo("P", "bike", [leaf({ power_unit: "pct_ftp", target_low: 90, target_high: 90 })], { ...emptyThresholds, ftp: 250 }, 300);
    expect(xml).toContain('PowerLow="0.75"');
  });
});

describe("buildZwo distance-ended step duration", () => {
  // Regression coverage for the same underlying bug as buildTcx's distance case above:
  // .zwo has no distance-duration concept at all (Zwift workouts are purely time-based), so
  // this genuinely needs an estimate - reuses the app's own distance+pace duration inference
  // (workoutTree.ts's leafDuration), not a duplicate implementation.
  it("estimates duration from threshold pace for a distance-ended running step", () => {
    const xml = buildZwo(
      "D",
      "run",
      [leaf({ end_type: "distance", duration: null, distance: 4000, target_type: "power", target_low: 80, target_high: 85 })],
      { ...emptyThresholds, thresholdPaceSecPerKm: 275 },
    );
    // avg 82.5% -> pace = 275 * 100/82.5 = 333.33 sec/km -> 4km * 333.33 = 1333s.
    expect(xml).toContain('Duration="1333"');
  });

  it("falls back to 0 for a distance-ended step with no threshold pace set", () => {
    const xml = buildZwo(
      "D",
      "run",
      [leaf({ end_type: "distance", duration: null, distance: 4000, target_type: "power", target_low: 80, target_high: 85 })],
      emptyThresholds,
    );
    expect(xml).toContain('Duration="0"');
  });
});
