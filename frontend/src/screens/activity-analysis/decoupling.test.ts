import { describe, expect, it } from "vitest";
import {
  bandMarkerPct,
  decouplingBand,
  decouplingChecks,
  decouplingHeatLevel,
  decouplingSummaryText,
  durabilityTiles,
  isNeverComputed,
  notScoredReasonText,
} from "./decoupling";
import type { Activity } from "../../api/types";
import type { DecouplingPrefs } from "./decoupling";

function baseAthlete(overrides: Partial<DecouplingPrefs> = {}): DecouplingPrefs {
  return {
    decoupling_vi_limit_bike: 1.06,
    decoupling_vi_limit_run: 1.04,
    decoupling_if_limit: 0.85,
    decoupling_min_steady_minutes: 60,
    decoupling_warm_air_temp: 25.0,
    decoupling_warm_skin_temp: 33.0,
    decoupling_hot_air_temp: 30.0,
    decoupling_hot_skin_temp: 34.0,
    ...overrides,
  };
}

function baseActivity(overrides: Partial<Activity> = {}): Activity {
  return {
    sport: "bike",
    avg_power: 200,
    moving_time: 4200,
    decoupling_pct: 5.3,
    ef_first: 1.53,
    ef_second: 1.45,
    steady_seconds: 3600,
    decoupling_qualified: true,
    decoupling_reasons: [],
    decoupling_vi: 1.04,
    decoupling_if: 0.74,
    decoupling_avg_temp: 28,
    decoupling_avg_core: 38.4,
    decoupling_avg_skin: 34.5,
    decoupling_warm: true,
    decoupling_hot: true,
    decoupling_hr_coverage_pct: 100,
    decoupling_power_coverage_pct: 100,
    decoupling_halves: [
      { start_s: 0, end_s: 1799, power: 196, hr: 128, ef: 1.53 },
      { start_s: 1800, end_s: 3599, power: 190, hr: 131, ef: 1.45 },
    ],
    durability: [],
    ...overrides,
  } as Activity;
}

describe("decouplingBand", () => {
  it("is good below 5%", () => {
    expect(decouplingBand(4.9)).toBe("good");
  });

  it("is moderate from 5% to 10% inclusive", () => {
    expect(decouplingBand(5)).toBe("moderate");
    expect(decouplingBand(10)).toBe("moderate");
  });

  it("is high above 10%", () => {
    expect(decouplingBand(10.1)).toBe("high");
  });

  it("treats a negative (improving) value as good", () => {
    expect(decouplingBand(-2)).toBe("good");
  });
});

describe("bandMarkerPct", () => {
  it("scales 0-15% to 0-100%", () => {
    expect(bandMarkerPct(7.5)).toBe(50);
  });

  it("caps at 100% beyond 15%", () => {
    expect(bandMarkerPct(22)).toBe(100);
  });
});

describe("decouplingChecks", () => {
  it("all four checks pass on a clean qualified session", () => {
    const checks = decouplingChecks(baseActivity(), baseAthlete());
    expect(checks.every((c) => c.pass)).toBe(true);
    expect(checks.map((c) => c.label)).toEqual([
      "VI 1.04 ≤ 1.06",
      "IF 0.74 ≤ 0.85",
      "60 min steady ≥ 60",
      "HR 100% · power 100%",
    ]);
  });

  it("uses the run VI limit for a run activity", () => {
    const checks = decouplingChecks(baseActivity({ sport: "run" }), baseAthlete());
    expect(checks[0].label).toContain("≤ 1.04");
  });

  it("fails the VI check when 'variable' is a reason", () => {
    const checks = decouplingChecks(baseActivity({ decoupling_reasons: ["variable"] }), baseAthlete());
    expect(checks[0].pass).toBe(false);
  });

  it("fails the IF check for either 'intensity' or 'no_threshold'", () => {
    expect(decouplingChecks(baseActivity({ decoupling_reasons: ["intensity"] }), baseAthlete())[1].pass).toBe(false);
    expect(decouplingChecks(baseActivity({ decoupling_reasons: ["no_threshold"] }), baseAthlete())[1].pass).toBe(false);
  });

  it("fails the steady check when 'short' is a reason", () => {
    expect(decouplingChecks(baseActivity({ decoupling_reasons: ["short"] }), baseAthlete())[2].pass).toBe(false);
  });

  it("fails the coverage check for either hr_coverage or power_coverage", () => {
    expect(decouplingChecks(baseActivity({ decoupling_reasons: ["hr_coverage"] }), baseAthlete())[3].pass).toBe(false);
    expect(decouplingChecks(baseActivity({ decoupling_reasons: ["power_coverage"] }), baseAthlete())[3].pass).toBe(false);
  });

  it("shows every check as null (never evaluated), not a false green pass, for an activity that was never computed", () => {
    const neverComputed = baseActivity({
      decoupling_qualified: false,
      decoupling_reasons: [],
      decoupling_vi: null,
      decoupling_if: null,
      steady_seconds: null,
      decoupling_hr_coverage_pct: null,
      decoupling_power_coverage_pct: null,
    });
    expect(decouplingChecks(neverComputed, baseAthlete()).every((c) => c.pass === null)).toBe(true);
  });

  it("uses the athlete's own configured thresholds, not the defaults, in every chip label", () => {
    const athlete = baseAthlete({
      decoupling_vi_limit_bike: 1.1,
      decoupling_if_limit: 0.9,
      decoupling_min_steady_minutes: 45,
    });
    const checks = decouplingChecks(baseActivity(), athlete);
    expect(checks.map((c) => c.label)).toEqual([
      "VI 1.04 ≤ 1.1",
      "IF 0.74 ≤ 0.9",
      "60 min steady ≥ 45",
      "HR 100% · power 100%",
    ]);
  });
});

describe("isNeverComputed", () => {
  it("is true for the Activity model's uncomputed default state", () => {
    expect(isNeverComputed(baseActivity({ decoupling_qualified: false, decoupling_reasons: [] }))).toBe(true);
  });

  it("is false once a real reason has been recorded, even though qualified is also false", () => {
    expect(isNeverComputed(baseActivity({ decoupling_qualified: false, decoupling_reasons: ["short"] }))).toBe(false);
  });

  it("is false for a qualified session", () => {
    expect(isNeverComputed(baseActivity({ decoupling_qualified: true, decoupling_reasons: [] }))).toBe(false);
  });
});

describe("decouplingHeatLevel", () => {
  it("is 'hot' when decoupling_hot is true, regardless of decoupling_warm", () => {
    expect(decouplingHeatLevel(baseActivity({ decoupling_hot: true, decoupling_warm: true }))).toBe("hot");
  });

  it("is 'warm' when only decoupling_warm is true", () => {
    expect(decouplingHeatLevel(baseActivity({ decoupling_hot: false, decoupling_warm: true }))).toBe("warm");
  });

  it("is 'none' when neither flag is set", () => {
    expect(decouplingHeatLevel(baseActivity({ decoupling_hot: false, decoupling_warm: false }))).toBe("none");
  });
});

describe("notScoredReasonText", () => {
  it("formats a too-variable reason with the sport's own VI limit", () => {
    const text = notScoredReasonText(baseActivity({ decoupling_reasons: ["variable"], decoupling_vi: 1.12 }), baseAthlete());
    expect(text).toBe("too variable (VI 1.12, limit 1.06)");
  });

  it("formats a too-intense reason", () => {
    const text = notScoredReasonText(baseActivity({ decoupling_reasons: ["intensity"], decoupling_if: 0.91 }), baseAthlete());
    expect(text).toBe("too intense (IF 0.91, limit 0.85)");
  });

  it("formats a too-short reason in whole minutes", () => {
    const text = notScoredReasonText(baseActivity({ decoupling_reasons: ["short"], steady_seconds: 2880 }), baseAthlete());
    expect(text).toBe("too short (48 min steady, needs 60)");
  });

  it("formats an HR coverage reason", () => {
    const text = notScoredReasonText(
      baseActivity({ decoupling_reasons: ["hr_coverage"], decoupling_hr_coverage_pct: 72 }),
      baseAthlete(),
    );
    expect(text).toBe("HR coverage 72% (needs 90%)");
  });

  it("says 'not yet computed' for an activity that predates this feature, not a false specific reason", () => {
    const text = notScoredReasonText(baseActivity({ decoupling_qualified: false, decoupling_reasons: [] }), baseAthlete());
    expect(text).toBe("not yet computed");
  });

  it("uses the athlete's own configured limit, not the default, in the reason text", () => {
    const text = notScoredReasonText(
      baseActivity({ decoupling_reasons: ["intensity"], decoupling_if: 0.91 }),
      baseAthlete({ decoupling_if_limit: 0.8 }),
    );
    expect(text).toBe("too intense (IF 0.91, limit 0.8)");
  });
});

describe("decouplingSummaryText", () => {
  it("describes a real efficiency drop with the right directions", () => {
    const text = decouplingSummaryText(baseActivity());
    expect(text).toBe("Efficiency fell from 1.53 to 1.45 W/bpm: heart rate rose 3 bpm while power eased 6 W.");
  });

  it("describes an efficiency improvement (negative decoupling) with reversed directions", () => {
    const activity = baseActivity({
      ef_first: 1.45,
      ef_second: 1.53,
      decoupling_halves: [
        { start_s: 0, end_s: 1799, power: 190, hr: 131, ef: 1.45 },
        { start_s: 1800, end_s: 3599, power: 196, hr: 128, ef: 1.53 },
      ],
    });
    const text = decouplingSummaryText(activity);
    expect(text).toBe("Efficiency rose from 1.45 to 1.53 W/bpm: heart rate fell 3 bpm while power rose 6 W.");
  });

  it("returns null when not qualified (no halves)", () => {
    expect(decouplingSummaryText(baseActivity({ ef_first: null, ef_second: null, decoupling_halves: [] }))).toBeNull();
  });
});

describe("durabilityTiles", () => {
  it("shows the 20-min power and the other windows when a threshold is reached", () => {
    const activity = baseActivity({
      durability: [
        { threshold: 1000, window_s: 300, power: 300, start_offset_s: 900 },
        { threshold: 1000, window_s: 1200, power: 233, start_offset_s: 900 },
        { threshold: 1000, window_s: 3600, power: 203, start_offset_s: 900 },
      ],
    });
    const tiles = durabilityTiles(activity);
    const tile = tiles.find((t) => t.label === "After 1,000 kJ")!;
    expect(tile.reached).toBe(true);
    expect(tile.value).toBe("233 W");
    expect(tile.sub).toBe("5 min 300 W · 60 min 203 W");
  });

  it("shows 'Not reached' with the activity's own total when a threshold has no rows at all", () => {
    const activity = baseActivity({ avg_power: 150, moving_time: 3600, durability: [] });
    const tiles = durabilityTiles(activity);
    const tile = tiles.find((t) => t.label === "After 1,000 kJ")!;
    expect(tile.reached).toBe(false);
    expect(tile.value).toBe("Not reached");
    expect(tile.sub).toBe("Ride total 540 kJ — needs 1,000 kJ + the effort window");
  });

  it("uses minutes basis and run/Run wording for a run activity", () => {
    const activity = baseActivity({ sport: "run", avg_power: 250, moving_time: 3000, durability: [] });
    const tiles = durabilityTiles(activity);
    expect(tiles.map((t) => t.label)).toEqual(["After 60 min", "After 90 min"]);
    expect(tiles[0].sub).toContain("Run total 50 min");
  });
});
