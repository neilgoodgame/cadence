import { describe, expect, it } from "vitest";
import { metricImproving, metricValue, visibleRollingSeries, yDomain } from "./durabilityChart";
import type { DurabilitySession } from "../../api/types";

function session(overrides: Partial<DurabilitySession> = {}): DurabilitySession {
  return {
    activity_id: "act_1",
    name: "Session",
    date: "2026-07-01",
    sport: "ride",
    decoupling_pct: 4.5,
    ef: 1.5,
    ef_first: 1.53,
    ef_second: 1.45,
    steady_seconds: 3600,
    hot: false,
    avg_temp: null,
    avg_core: null,
    ...overrides,
  };
}

describe("metricValue", () => {
  it("reads decoupling_pct for the dec metric", () => {
    expect(metricValue(session({ decoupling_pct: 7.2 }), "dec")).toBe(7.2);
  });

  it("reads ef for the ef metric", () => {
    expect(metricValue(session({ ef: 1.6 }), "ef")).toBe(1.6);
  });
});

describe("metricImproving", () => {
  it("decoupling improves when it falls", () => {
    expect(metricImproving("dec", 3, 5)).toBe(true);
    expect(metricImproving("dec", 6, 5)).toBe(false);
  });

  it("ef improves when it rises", () => {
    expect(metricImproving("ef", 1.6, 1.5)).toBe(true);
    expect(metricImproving("ef", 1.4, 1.5)).toBe(false);
  });
});

describe("yDomain", () => {
  it("is a fixed 0-15 for decoupling regardless of the data", () => {
    expect(yDomain("dec", [session({ decoupling_pct: 40 })])).toEqual([0, 15]);
  });

  it("pads the data range by 0.05 for EF", () => {
    const sessions = [session({ ef: 1.4 }), session({ ef: 1.8 })];
    const [min, max] = yDomain("ef", sessions);
    expect(min).toBeCloseTo(1.35, 5);
    expect(max).toBeCloseTo(1.85, 5);
  });

  it("falls back to a default range when there is no EF data", () => {
    expect(yDomain("ef", [session({ ef: null })])).toEqual([1, 2]);
  });
});

describe("visibleRollingSeries", () => {
  const rolling = {
    ride: [{ date: "2026-07-01", decoupling_pct: 4, ef: 1.5 }],
    run: [{ date: "2026-07-02", decoupling_pct: 6, ef: 1.3 }],
  };

  it("includes both sports when the filter is all", () => {
    expect(visibleRollingSeries(rolling, "all").map((s) => s.sport)).toEqual(["ride", "run"]);
  });

  it("includes only the selected sport otherwise", () => {
    expect(visibleRollingSeries(rolling, "ride").map((s) => s.sport)).toEqual(["ride"]);
    expect(visibleRollingSeries(rolling, "run").map((s) => s.sport)).toEqual(["run"]);
  });

  it("omits a sport the response doesn't include at all", () => {
    expect(visibleRollingSeries({ ride: rolling.ride }, "all").map((s) => s.sport)).toEqual(["ride"]);
  });
});
