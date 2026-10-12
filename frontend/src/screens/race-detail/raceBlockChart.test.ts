import { describe, expect, it } from "vitest";
import { buildBlockChart, currentWeekIndex, metricValue, splitProjectedLine } from "./raceBlockChart";
import type { RaceBlockWeek } from "../../api/types";

function week(overrides: Partial<RaceBlockWeek> = {}): RaceBlockWeek {
  return {
    week_start: "2026-01-05",
    tss: 100,
    tss_races: 0,
    run_km: 40,
    run_moving_s: 14400,
    run_count: 4,
    run_avg_pace_s_per_km: 360,
    run_long_km: 20,
    ride_km: null,
    ride_moving_s: null,
    ride_count: null,
    ride_avg_power_w: null,
    ride_long_km: null,
    hours: 4,
    sessions: 4,
    ctl: 60,
    atl: 55,
    planned: false,
    races: [],
    ...overrides,
  };
}

describe("metricValue", () => {
  it("sums run and ride distance", () => {
    expect(metricValue(week({ run_km: 10, ride_km: 20 }), "distance")).toBe(30);
  });

  it("is null for distance when both sports have no data (unscheduled planned week)", () => {
    expect(metricValue(week({ run_km: null, ride_km: null }), "distance")).toBeNull();
  });

  it("returns tss and hours directly", () => {
    expect(metricValue(week({ tss: 123 }), "tss")).toBe(123);
    expect(metricValue(week({ hours: 5.5 }), "time")).toBe(5.5);
  });
});

describe("buildBlockChart", () => {
  it("produces one bar per week, in order", () => {
    const weeks = [week({ week_start: "2026-01-05" }), week({ week_start: "2026-01-12" })];
    const chart = buildBlockChart(weeks, null, "tss");
    expect(chart.bars).toHaveLength(2);
    expect(chart.bars[0].index).toBe(0);
    expect(chart.bars[1].index).toBe(1);
  });

  it("clamps the race portion of a bar to the week's own total", () => {
    const weeks = [week({ tss: 50, tss_races: 80 })]; // a race week can exceed a short block's own total
    const chart = buildBlockChart(weeks, null, "tss");
    expect(chart.bars[0].raceValue).toBe(50);
  });

  it("race portion is 0 for a non-tss metric", () => {
    const weeks = [week({ tss_races: 80 })];
    const chart = buildBlockChart(weeks, null, "distance");
    expect(chart.bars[0].raceValue).toBe(0);
  });

  it("yMax accounts for the comparison block too", () => {
    const weeks = [week({ tss: 50 })];
    const comparison = [week({ tss: 150 })];
    const chart = buildBlockChart(weeks, comparison, "tss");
    expect(chart.yMax).toBe(150);
  });

  it("flags a week with a race", () => {
    const weeks = [week({ races: [{ id: "race_1", name: "Tune-up", priority: "B", result_s: null }] })];
    const chart = buildBlockChart(weeks, null, "tss");
    expect(chart.bars[0].hasRace).toBe(true);
  });
});

describe("splitProjectedLine", () => {
  const p = (index: number, planned: boolean) => ({ index, ctl: index, planned });

  it("is all-actual when nothing is planned", () => {
    const { actual, projected } = splitProjectedLine([p(0, false), p(1, false)]);
    expect(actual).toHaveLength(2);
    expect(projected).toHaveLength(0);
  });

  it("is all-projected when the whole line is planned", () => {
    const { actual, projected } = splitProjectedLine([p(0, true), p(1, true)]);
    expect(actual).toHaveLength(0);
    expect(projected).toHaveLength(2);
  });

  it("joins the two segments at the last actual point for visual continuity", () => {
    const { actual, projected } = splitProjectedLine([p(0, false), p(1, false), p(2, true), p(3, true)]);
    expect(actual.map((pt) => pt.index)).toEqual([0, 1]);
    expect(projected.map((pt) => pt.index)).toEqual([1, 2, 3]);
  });
});

describe("currentWeekIndex", () => {
  it("finds the week containing today", () => {
    const weeks = [week({ week_start: "2026-01-05" }), week({ week_start: "2026-01-12" })];
    expect(currentWeekIndex(weeks, new Date("2026-01-14T00:00:00Z"))).toBe(1);
  });

  it("returns -1 when today is outside the block", () => {
    const weeks = [week({ week_start: "2026-01-05" })];
    expect(currentWeekIndex(weeks, new Date("2026-03-01T00:00:00Z"))).toBe(-1);
  });
});
