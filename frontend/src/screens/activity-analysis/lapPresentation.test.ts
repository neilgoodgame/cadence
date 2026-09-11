import { describe, expect, it } from "vitest";
import type { Athlete, Lap } from "../../api/types";
import { compliancePct, groupLaps, stepZonePct, summarizeSteps } from "./lapPresentation";

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
    step_duration: null,
    step_distance: null,
    ...overrides,
  };
}

function gorbyLaps(): Lap[] {
  const laps: Lap[] = [
    lap({ index: 1, duration: 600, workout_step_id: 1, step_kind: "warmup", step_target_type: "power", step_target_low: 50, step_target_high: 70, step_power_unit: "pct_ftp", step_duration: 600 }),
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
        step_duration: 300,
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
        step_duration: 300,
        avg_power: 140,
      }),
    );
  }
  laps.push(lap({ index: laps.length + 1, duration: 34, distance_km: 0.2 }));
  return laps;
}

const athlete = { ftp: 250, max_hr: 180 } as Athlete;
const powerReference = 250; // stands in for the activity-date-scoped FTP the caller now resolves

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
    expect(stepZonePct(lap({ step_target_type: "power", step_target_low: 110, step_target_high: 110, step_power_unit: "pct_ftp" }), powerReference)).toBe(110);
  });

  it("converts a watts power target using the activity-scoped power reference", () => {
    const pct = stepZonePct(lap({ step_target_type: "power", step_target_low: 275, step_target_high: 275, step_power_unit: "watts" }), powerReference);
    expect(pct).toBeCloseTo(110);
  });

  it("returns null for a watts target with no known power reference", () => {
    expect(stepZonePct(lap({ step_target_type: "power", step_target_low: 275, step_power_unit: "watts" }), null)).toBeNull();
  });

  it("returns null when there's no target at all", () => {
    expect(stepZonePct(lap({}), powerReference)).toBeNull();
  });
});

describe("compliancePct", () => {
  it("computes power compliance against a %FTP target", () => {
    // target mid = 110% FTP = 275W, actual 280W -> ~102%
    const pct = compliancePct(
      lap({ step_target_type: "power", step_target_low: 110, step_target_high: 110, step_power_unit: "pct_ftp", avg_power: 280 }),
      athlete,
      powerReference,
    );
    expect(pct).toBe(Math.round((280 / 275) * 100));
  });

  it("computes HR compliance against a %max-HR target", () => {
    // target mid = 80% of 180 = 144bpm, actual 150bpm
    const pct = compliancePct(lap({ step_target_type: "hr", step_target_low: 80, step_target_high: 80, avg_hr: 150 }), athlete, powerReference);
    expect(pct).toBe(Math.round((150 / 144) * 100));
  });

  it("returns null for a pace target (no threshold-pace conversion attempted)", () => {
    expect(compliancePct(lap({ step_target_type: "pace", step_target_low: 90, step_target_high: 90 }), athlete, powerReference)).toBeNull();
  });

  it("returns null when there's no power reference to compare a %FTP target against", () => {
    expect(compliancePct(lap({ step_target_type: "power", step_target_low: 110, avg_power: 280 }), athlete, null)).toBeNull();
  });

  // Regression test for a real bug: an activity recorded before the athlete's most recent FTP
  // change was showing every lap's compliance against the athlete's *current* FTP instead of
  // whatever was true on the activity's own date - inflating every single badge (warmup through
  // work blocks alike) by the same proportion. powerReference must come from the caller
  // (activity-scoped GET /v1/athletes/{id}/zones?activity_id=...), never athlete.ftp directly.
  it("uses powerReference, not the athlete's live FTP, for a %FTP target", () => {
    const staleAthlete = { ftp: 400, max_hr: 180 } as Athlete; // athlete's FTP has since changed
    const historicalReference = 250; // what FTP actually was on the activity's own date
    const pct = compliancePct(
      lap({ step_target_type: "power", step_target_low: 100, step_target_high: 100, step_power_unit: "pct_ftp", avg_power: 250 }),
      staleAthlete,
      historicalReference,
    );
    expect(pct).toBe(100); // spot on against the historical 250W reference, not 62% against 400W
  });
});

describe("summarizeSteps", () => {
  it("groups by step definition (not step_kind), one row per distinct step, in first-seen order", () => {
    const rows = summarizeSteps(gorbyLaps(), athlete, powerReference);

    expect(rows.map((r) => r.label)).toEqual(["Warm-up", "Work block", "Recovery", "Other"]);
    expect(rows.map((r) => r.count)).toEqual([1, 5, 5, 1]);
  });

  it("averages avg_power across every rep of a step, not just one", () => {
    const rows = summarizeSteps(gorbyLaps(), athlete, powerReference);
    const block = rows.find((r) => r.label === "Work block")!;

    expect(block.avgPower).toBe(280); // every block rep is 280W in the fixture
  });

  it("keeps two different steps of the same kind separate (e.g. a pyramid with two distinct block targets)", () => {
    const laps = [
      lap({ index: 1, workout_step_id: 10, step_kind: "block", step_target_type: "power", step_target_low: 100, step_target_high: 100, step_duration: 300, avg_power: 250 }),
      lap({ index: 2, workout_step_id: 11, step_kind: "block", step_target_type: "power", step_target_low: 120, step_target_high: 120, step_duration: 300, avg_power: 300 }),
    ];

    const rows = summarizeSteps(laps, athlete, powerReference);

    expect(rows).toHaveLength(2);
    expect(rows[0].avgPower).toBe(250);
    expect(rows[1].avgPower).toBe(300);
  });

  // Regression test for a real bug: a workout authored as several individual leaf steps sharing
  // the same target (rather than the app's `repeat` construct) gives each occurrence a distinct
  // workout_step_id - grouping by raw id showed three separate "1x avg" cards for what is really
  // one repeated step, instead of one true average.
  it("merges laps from distinct WorkoutStep rows that share the same kind/target/duration", () => {
    const laps = [
      lap({ index: 1, workout_step_id: 90, step_kind: "rec", step_target_type: "power", step_target_low: 65, step_target_high: 65, step_power_unit: "pct_ftp", step_duration: 60, avg_power: 172, avg_hr: 107 }),
      lap({ index: 2, workout_step_id: 94, step_kind: "rec", step_target_type: "power", step_target_low: 65, step_target_high: 65, step_power_unit: "pct_ftp", step_duration: 60, avg_power: 171, avg_hr: 106 }),
      lap({ index: 3, workout_step_id: 98, step_kind: "rec", step_target_type: "power", step_target_low: 65, step_target_high: 65, step_power_unit: "pct_ftp", step_duration: 60, avg_power: 171, avg_hr: 106 }),
    ];

    const rows = summarizeSteps(laps, athlete, powerReference);

    expect(rows).toHaveLength(1);
    expect(rows[0].count).toBe(3);
    expect(rows[0].avgPower).toBeCloseTo((172 + 171 + 171) / 3);
  });

  // Regression test for the flip side: two steps that only coincidentally share a %FTP target
  // but are structurally different durations (a short block within a ladder vs. a long sustained
  // block later in the same ride) must not be blended together.
  it("keeps same-target steps of different planned duration separate", () => {
    const laps = [
      lap({ index: 1, workout_step_id: 16, step_kind: "block", step_target_type: "power", step_target_low: 100, step_target_high: 100, step_power_unit: "pct_ftp", step_duration: 20, avg_power: 258 }),
      lap({ index: 2, workout_step_id: 26, step_kind: "block", step_target_type: "power", step_target_low: 100, step_target_high: 100, step_power_unit: "pct_ftp", step_duration: 600, avg_power: 260 }),
    ];

    const rows = summarizeSteps(laps, athlete, powerReference);

    expect(rows).toHaveLength(2);
  });
});
