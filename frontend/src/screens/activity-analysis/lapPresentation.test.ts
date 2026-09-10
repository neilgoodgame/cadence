import { describe, expect, it } from "vitest";
import type { Athlete, Lap } from "../../api/types";
import { compliancePct, groupLaps, stepZonePct } from "./lapPresentation";

// Mirrors the real "The Gorby" structure this feature was built against: warmup, then
// 5x[block @110% FTP, rec @52.5% FTP], then one trailing unlinked lap.
function lap(overrides: Partial<Lap>): Lap {
  return {
    index: 1,
    duration: 300,
    distance_km: 1,
    avg_hr: null,
    avg_power: null,
    workout_step_id: null,
    repeat_index: null,
    step_kind: null,
    step_target_type: null,
    step_target_low: null,
    step_target_high: null,
    step_power_unit: null,
    ...overrides,
  };
}

function gorbyLaps(): Lap[] {
  const laps: Lap[] = [
    lap({ index: 1, duration: 600, workout_step_id: 1, step_kind: "warmup", step_target_type: "power", step_target_low: 50, step_target_high: 70, step_power_unit: "pct_ftp" }),
  ];
  for (let rep = 1; rep <= 5; rep++) {
    laps.push(
      lap({
        index: laps.length + 1,
        workout_step_id: 2,
        repeat_index: rep,
        step_kind: "block",
        step_target_type: "power",
        step_target_low: 110,
        step_target_high: 110,
        step_power_unit: "pct_ftp",
        avg_power: 280,
      }),
    );
    laps.push(
      lap({
        index: laps.length + 1,
        workout_step_id: 3,
        repeat_index: rep,
        step_kind: "rec",
        step_target_type: "power",
        step_target_low: 50,
        step_target_high: 55,
        step_power_unit: "pct_ftp",
        avg_power: 140,
      }),
    );
  }
  laps.push(lap({ index: laps.length + 1, duration: 34, distance_km: 0.2 }));
  return laps;
}

const athlete = { ftp: 250, max_hr: 180 } as Athlete;

describe("groupLaps", () => {
  it("collapses a 5x[block, rec] cycle into one repeat group, leaving warmup/trailing as singles", () => {
    const groups = groupLaps(gorbyLaps());

    expect(groups).toHaveLength(3);
    expect(groups[0]).toMatchObject({ type: "single", lap: { step_kind: "warmup" } });
    expect(groups[1].type).toBe("repeat");
    if (groups[1].type === "repeat") {
      expect(groups[1].reps).toHaveLength(5);
      expect(groups[1].reps[0].map((l) => l.step_kind)).toEqual(["block", "rec"]);
      expect(groups[1].reps[4].map((l) => l.repeat_index)).toEqual([5, 5]);
    }
    expect(groups[2]).toMatchObject({ type: "single", lap: { workout_step_id: null } });
  });

  it("falls back to individual rows when a repeat_index === 1 lap has no matching rep 2", () => {
    const laps = [lap({ index: 1, workout_step_id: 5, repeat_index: 1, step_kind: "block" })];

    const groups = groupLaps(laps);

    expect(groups).toEqual([{ type: "single", lap: laps[0] }]);
  });

  it("treats laps with no repeat_index at all as plain singles", () => {
    const laps = [lap({ index: 1 }), lap({ index: 2 })];

    expect(groupLaps(laps)).toEqual([
      { type: "single", lap: laps[0] },
      { type: "single", lap: laps[1] },
    ]);
  });
});

describe("stepZonePct", () => {
  it("passes a %FTP power target through directly", () => {
    expect(stepZonePct(lap({ step_target_type: "power", step_target_low: 110, step_target_high: 110, step_power_unit: "pct_ftp" }), athlete)).toBe(110);
  });

  it("converts a watts power target using the athlete's FTP", () => {
    const pct = stepZonePct(lap({ step_target_type: "power", step_target_low: 275, step_target_high: 275, step_power_unit: "watts" }), athlete);
    expect(pct).toBeCloseTo(110);
  });

  it("returns null for a watts target with no known FTP", () => {
    const noFtp = { ftp: null, max_hr: 180 } as Athlete;
    expect(stepZonePct(lap({ step_target_type: "power", step_target_low: 275, step_power_unit: "watts" }), noFtp)).toBeNull();
  });

  it("returns null when there's no target at all", () => {
    expect(stepZonePct(lap({}), athlete)).toBeNull();
  });
});

describe("compliancePct", () => {
  it("computes power compliance against a %FTP target", () => {
    // target mid = 110% FTP = 275W, actual 280W -> ~102%
    const pct = compliancePct(
      lap({ step_target_type: "power", step_target_low: 110, step_target_high: 110, step_power_unit: "pct_ftp", avg_power: 280 }),
      athlete,
    );
    expect(pct).toBe(Math.round((280 / 275) * 100));
  });

  it("computes HR compliance against a %max-HR target", () => {
    // target mid = 80% of 180 = 144bpm, actual 150bpm
    const pct = compliancePct(lap({ step_target_type: "hr", step_target_low: 80, step_target_high: 80, avg_hr: 150 }), athlete);
    expect(pct).toBe(Math.round((150 / 144) * 100));
  });

  it("returns null for a pace target (no threshold-pace conversion attempted)", () => {
    expect(compliancePct(lap({ step_target_type: "pace", step_target_low: 90, step_target_high: 90 }), athlete)).toBeNull();
  });

  it("returns null when the athlete has no FTP to compare a %FTP target against", () => {
    const noFtp = { ftp: null, max_hr: 180 } as Athlete;
    expect(compliancePct(lap({ step_target_type: "power", step_target_low: 110, avg_power: 280 }), noFtp)).toBeNull();
  });
});
