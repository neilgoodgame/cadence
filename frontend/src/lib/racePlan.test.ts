import { describe, expect, it } from "vitest";
import { computeRacePlan } from "./racePlan";

const MARATHON_KM = 42.195;
const GOAL_3H30 = 3.5 * 3600;

describe("computeRacePlan halves", () => {
  it("even split halves sum to the goal time", () => {
    const plan = computeRacePlan({
      distanceKm: MARATHON_KM,
      goalTimeS: GOAL_3H30,
      strategy: "even",
      carbsGPerH: 60,
      sweatRateLPerH: 0.9,
    });
    expect(plan.halves.firstS + plan.halves.secondS).toBeCloseTo(GOAL_3H30, 1);
    expect(plan.halves.firstS).toBeCloseTo(plan.halves.secondS, 1);
  });

  it("negative split halves still sum to the goal time, with a slower first half", () => {
    const plan = computeRacePlan({
      distanceKm: MARATHON_KM,
      goalTimeS: GOAL_3H30,
      strategy: "negative",
      carbsGPerH: 60,
      sweatRateLPerH: 0.9,
    });
    expect(plan.halves.firstS + plan.halves.secondS).toBeCloseTo(GOAL_3H30, 1);
    expect(plan.halves.firstS).toBeGreaterThan(plan.halves.secondS);
  });
});

describe("computeRacePlan gel schedule", () => {
  it("stops at least 10 minutes before the finish", () => {
    const plan = computeRacePlan({
      distanceKm: MARATHON_KM,
      goalTimeS: GOAL_3H30,
      strategy: "even",
      carbsGPerH: 90, // frequent gels, to make sure the cutoff is actually exercised
      sweatRateLPerH: 0.9,
    });
    const lastGel = plan.fuel.gels.at(-1);
    expect(lastGel).toBeDefined();
    expect(lastGel!.atS).toBeLessThanOrEqual(GOAL_3H30 - 10 * 60);
  });

  it("spaces gels further apart at a lower carbs-per-hour rate", () => {
    const slow = computeRacePlan({
      distanceKm: MARATHON_KM,
      goalTimeS: GOAL_3H30,
      strategy: "even",
      carbsGPerH: 45,
      sweatRateLPerH: 0.9,
    });
    const fast = computeRacePlan({
      distanceKm: MARATHON_KM,
      goalTimeS: GOAL_3H30,
      strategy: "even",
      carbsGPerH: 90,
      sweatRateLPerH: 0.9,
    });
    expect(slow.fuel.intervalMin).toBeGreaterThan(fast.fuel.intervalMin);
    expect(slow.fuel.gels.length).toBeLessThan(fast.fuel.gels.length);
  });
});

describe("computeRacePlan fluid", () => {
  it("is capped at 0.8 L/h even for a very high sweat rate", () => {
    const plan = computeRacePlan({
      distanceKm: MARATHON_KM,
      goalTimeS: GOAL_3H30,
      strategy: "even",
      carbsGPerH: 60,
      sweatRateLPerH: 3.0,
    });
    expect(plan.fluidLPerH).toBe(0.8);
  });

  it("follows 0.7 x sweat rate below the cap", () => {
    const plan = computeRacePlan({
      distanceKm: MARATHON_KM,
      goalTimeS: GOAL_3H30,
      strategy: "even",
      carbsGPerH: 60,
      sweatRateLPerH: 0.5,
    });
    expect(plan.fluidLPerH).toBeCloseTo(0.35, 5);
  });

  it("sodium is proportional to fluid at 700mg per litre", () => {
    const plan = computeRacePlan({
      distanceKm: MARATHON_KM,
      goalTimeS: GOAL_3H30,
      strategy: "even",
      carbsGPerH: 60,
      sweatRateLPerH: 0.5,
    });
    expect(plan.sodiumMgPerH).toBeCloseTo(0.35 * 700, 5);
  });
});

describe("computeRacePlan table", () => {
  it("the last row is the finish and lands exactly on the goal time", () => {
    const plan = computeRacePlan({
      distanceKm: MARATHON_KM,
      goalTimeS: GOAL_3H30,
      strategy: "even",
      carbsGPerH: 60,
      sweatRateLPerH: 0.9,
    });
    const last = plan.table.at(-1);
    expect(last!.isFinish).toBe(true);
    expect(last!.clockS).toBeCloseTo(GOAL_3H30, 1);
    expect(plan.table.filter((r) => r.isFinish)).toHaveLength(1);
  });

  it("row clock times are strictly increasing", () => {
    const plan = computeRacePlan({
      distanceKm: MARATHON_KM,
      goalTimeS: GOAL_3H30,
      strategy: "negative",
      carbsGPerH: 60,
      sweatRateLPerH: 0.9,
    });
    for (let i = 1; i < plan.table.length; i++) {
      expect(plan.table[i].clockS).toBeGreaterThan(plan.table[i - 1].clockS);
    }
  });
});
