import { describe, expect, it } from "vitest";
import type { RaceBlockWeek } from "../../api/types";
import { buildTrainingLoadChartSvg } from "./raceReportChart";

function week(overrides: Partial<RaceBlockWeek> = {}): RaceBlockWeek {
  return {
    week_start: "2026-01-19",
    tss: 320,
    tss_races: null,
    run_km: 48.5,
    run_moving_s: 15600,
    run_count: 5,
    run_avg_pace_s_per_km: 322,
    run_long_km: 16,
    run_tss: 300,
    ride_km: null,
    ride_moving_s: null,
    ride_count: null,
    ride_avg_power_w: null,
    ride_long_km: null,
    ride_tss: null,
    hours: 4.3,
    sessions: 5,
    ctl: 62,
    atl: 58,
    planned: false,
    races: [],
    ...overrides,
  };
}

describe("buildTrainingLoadChartSvg", () => {
  it("renders an SVG with one bar segment per week and no CTL line", () => {
    const svg = buildTrainingLoadChartSvg([week(), week({ week_start: "2026-01-26", tss: 280 })], null, null, "tss");
    expect(svg).toContain("<svg");
    expect(svg).toContain("Weekly training load (TSS)");
    // No CTL polyline/path is drawn - this chart intentionally drops it.
    expect(svg).not.toContain("CTL");
    expect(svg).not.toContain("<path");
  });

  it("includes a dashed comparison legend and ghost bars when a comparison block is given", () => {
    const svg = buildTrainingLoadChartSvg([week()], [week({ tss: 250 })], "Tunbridge Wells Half 2025", "tss");
    expect(svg).toContain("Tunbridge Wells Half 2025");
    expect(svg).toContain('stroke-dasharray="2,2"'); // comparison legend swatch
    expect(svg).toContain('stroke-dasharray="3,3"'); // comparison ghost bar outline
  });

  it("omits the comparison legend entirely when there is no comparison block", () => {
    const svg = buildTrainingLoadChartSvg([week()], null, null, "tss");
    expect(svg).not.toContain("Comparison");
  });

  it("titles the chart by metric for distance and time", () => {
    const distanceSvg = buildTrainingLoadChartSvg([week()], null, null, "distance");
    expect(distanceSvg).toContain("Weekly distance (km)");
    expect(distanceSvg).not.toContain("Weekly training load (TSS)");

    const timeSvg = buildTrainingLoadChartSvg([week()], null, null, "time");
    expect(timeSvg).toContain("Weekly time (hours)");
  });

  it("defaults to the TSS metric when none is given", () => {
    const svg = buildTrainingLoadChartSvg([week()], null, null);
    expect(svg).toContain("Weekly training load (TSS)");
  });

  it("titles the chart and narrows the legend when filtered to a single sport", () => {
    const mixedWeek = week({ ride_km: 40, ride_moving_s: 5400, ride_count: 1, ride_tss: 90, tss: 390 });
    const runOnly = buildTrainingLoadChartSvg([mixedWeek], null, null, "tss", "run");
    expect(runOnly).toContain("Run only");
    expect(runOnly).not.toContain("Ride");
    expect(runOnly).not.toContain("Other");

    const rideOnly = buildTrainingLoadChartSvg([mixedWeek], null, null, "tss", "ride");
    expect(rideOnly).toContain("Ride only");
    expect(rideOnly).not.toContain(">Run<");
  });

  it("shows the full Run/Ride/Other legend for the default 'all' sport", () => {
    const svg = buildTrainingLoadChartSvg([week()], null, null, "tss", "all");
    expect(svg).toContain(">Run<");
    expect(svg).toContain(">Ride<");
    expect(svg).toContain(">Other<");
    expect(svg).not.toContain("only");
  });
});
