import { describe, expect, it } from "vitest";
import type { LeafStep, RepeatGroup, WorkoutStep } from "../../api/types";
import { flattenLeaves, normalizePowerUnits, stepCount, totalDuration, totalTss } from "./workoutTree";

// Same worked examples as backend/workouts/tests.py and WorkoutCalculationsTest.java -
// keeps all three duration/TSS implementations honest against each other.

function leaf(overrides: Partial<LeafStep> = {}): LeafStep {
  return {
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

function group(repeat: number, children: WorkoutStep[]): RepeatGroup {
  return { kind: "repeat", repeat, note: "", children };
}

describe("totalDuration / totalTss", () => {
  it("worked example: repeat group of a single power block", () => {
    const steps = [group(4, [leaf({ duration: 300, target_low: 100, target_high: 100 })])];
    expect(totalDuration(steps, "bike", null)).toBe(1200);
    expect(totalTss(steps, "bike", null)).toBe(33);
  });

  it("distance and manual steps contribute zero duration without a threshold pace", () => {
    const steps = [
      leaf({ end_type: "distance", duration: null, distance: 5000 }),
      leaf({ end_type: "manual", duration: null, target_type: "open" }),
    ];
    expect(totalDuration(steps, "run", null)).toBe(0);
    expect(totalTss(steps, "run", null)).toBe(0);
  });

  it("ramp uses the low/high midpoint", () => {
    const steps = [leaf({ duration: 3600, target_low: 50, target_high: 70 })];
    expect(totalDuration(steps, "bike", null)).toBe(3600);
    expect(totalTss(steps, "bike", null)).toBe(36);
  });

  it("nested repeat groups multiply and sum", () => {
    const steps = [
      group(2, [
        group(4, [leaf({ duration: 240, target_low: 100, target_high: 100 })]),
        leaf({ kind: "rec", duration: 200, target_low: 50, target_high: 50 }),
      ]),
    ];
    expect(totalDuration(steps, "bike", null)).toBe(2 * (4 * 240 + 200));
    expect(totalTss(steps, "bike", null)).toBe(56);
  });

  // Regression coverage for a real bug found live: a running workout's distance-ended
  // power-targeted step (e.g. "4km at 80-85% critical run power") showed 0:00 duration and 0
  // TSS. Running power % and threshold-pace % share the same %-of-60min-effort scale, so this
  // is now inferred from the athlete's threshold pace, scoped to sport === "run" only.
  it("infers duration for a run's distance-ended power step from threshold pace", () => {
    const steps = [leaf({ end_type: "distance", duration: null, distance: 4000, target_type: "power", target_low: 80, target_high: 85 })];
    // avg 82.5% -> pace = 275 * 100/82.5 = 333.33 sec/km -> 4km * 333.33 = 1333s.
    expect(totalDuration(steps, "run", 275)).toBe(1333);
    expect(totalTss(steps, "run", 275)).not.toBe(0);
  });

  it("infers duration for a run's distance-ended pace step from threshold pace", () => {
    const steps = [leaf({ end_type: "distance", duration: null, distance: 1000, target_type: "pace", target_low: 105, target_high: 106 })];
    expect(totalDuration(steps, "run", 275)).toBe(261); // 1km @ (275 * 100/105.5) = 260.66s/km
  });

  it("still contributes zero for a bike's distance-ended power step", () => {
    const steps = [leaf({ end_type: "distance", duration: null, distance: 40000, target_type: "power", target_low: 90, target_high: 90 })];
    expect(totalDuration(steps, "bike", 275)).toBe(0);
  });
});

describe("normalizePowerUnits", () => {
  it("converts a watts-unit power leaf to its %FTP-equivalent using the real reference", () => {
    const steps = [leaf({ power_unit: "watts", target_low: 200, target_high: 250 })];
    const normalized = normalizePowerUnits(steps, 250);
    const [normalizedLeaf] = normalized as LeafStep[];
    expect(normalizedLeaf.power_unit).toBe("pct_ftp");
    expect(normalizedLeaf.target_low).toBe(80);
    expect(normalizedLeaf.target_high).toBe(100);
  });

  it("falls back to the placeholder reference when the athlete hasn't set a real one", () => {
    const steps = [leaf({ power_unit: "watts", target_low: 265, target_high: 265 })];
    const normalized = normalizePowerUnits(steps, null);
    expect((normalized[0] as LeafStep).target_low).toBe(100);
  });

  it("leaves pct_ftp-unit and non-power steps untouched", () => {
    const steps = [leaf({ power_unit: "pct_ftp", target_low: 80, target_high: 90 }), leaf({ target_type: "hr", target_low: 70, target_high: 70 })];
    expect(normalizePowerUnits(steps, 250)).toEqual(steps);
  });

  it("normalizes watts-unit leaves nested inside a repeat group, and TSS matches an equivalent pct_ftp workout", () => {
    const wattsSteps = [group(4, [leaf({ power_unit: "watts", target_low: 250, target_high: 250, duration: 300 })])];
    const pctSteps = [group(4, [leaf({ power_unit: "pct_ftp", target_low: 100, target_high: 100, duration: 300 })])];
    const normalizedWatts = normalizePowerUnits(wattsSteps, 250);
    expect(totalTss(normalizedWatts, "bike", null)).toBe(totalTss(pctSteps, "bike", null));
    expect(totalDuration(normalizedWatts, "bike", null)).toBe(totalDuration(pctSteps, "bike", null));
  });
});

describe("stepCount / flattenLeaves", () => {
  it("counts leaves inside repeat groups, multiplied by repeat", () => {
    const steps = [leaf(), group(4, [leaf(), leaf({ kind: "rec" })])];
    expect(stepCount(steps)).toBe(1 + 4 * 2);
  });

  it("flattens nested repeat groups into their unrolled leaf sequence", () => {
    const steps = [group(2, [leaf({ kind: "block" }), leaf({ kind: "rec" })])];
    expect(flattenLeaves(steps).map((s) => s.kind)).toEqual(["block", "rec", "block", "rec"]);
  });
});
