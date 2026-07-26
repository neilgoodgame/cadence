import { describe, expect, it } from "vitest";
import type { LeafStep, RepeatGroup, WorkoutStep } from "../../api/types";
import { flattenLeaves, stepCount, totalDuration, totalTss } from "./workoutTree";

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
    expect(totalDuration(steps)).toBe(1200);
    expect(totalTss(steps)).toBe(33);
  });

  it("distance and manual steps contribute zero duration", () => {
    const steps = [
      leaf({ end_type: "distance", duration: null, distance: 5000 }),
      leaf({ end_type: "manual", duration: null, target_type: "open" }),
    ];
    expect(totalDuration(steps)).toBe(0);
    expect(totalTss(steps)).toBe(0);
  });

  it("ramp uses the low/high midpoint", () => {
    const steps = [leaf({ duration: 3600, target_low: 50, target_high: 70 })];
    expect(totalDuration(steps)).toBe(3600);
    expect(totalTss(steps)).toBe(36);
  });

  it("nested repeat groups multiply and sum", () => {
    const steps = [
      group(2, [
        group(4, [leaf({ duration: 240, target_low: 100, target_high: 100 })]),
        leaf({ kind: "rec", duration: 200, target_low: 50, target_high: 50 }),
      ]),
    ];
    expect(totalDuration(steps)).toBe(2 * (4 * 240 + 200));
    expect(totalTss(steps)).toBe(56);
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
