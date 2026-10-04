import { describe, expect, it } from "vitest";
import {
  bandMarkerPct,
  decouplingBand,
  decouplingChecks,
  decouplingSummaryText,
  durabilityTiles,
  isNeverComputed,
  notScoredReasonText,
} from "./decoupling";
import type { Activity } from "../../api/types";

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
    const checks = decouplingChecks(baseActivity());
    expect(checks.every((c) => c.pass)).toBe(true);
    expect(checks.map((c) => c.label)).toEqual([
      "VI 1.04 ≤ 1.06",
      "IF 0.74 ≤ 0.85",
      "60 min steady ≥ 60",
      "HR 100% · power 100%",
    ]);
  });

  it("uses the run VI limit for a run activity", () => {
    const checks = decouplingChecks(baseActivity({ sport: "run" }));
    expect(checks[0].label).toContain("≤ 1.04");
  });

  it("fails the VI check when 'variable' is a reason", () => {
    const checks = decouplingChecks(baseActivity({ decoupling_reasons: ["variable"] }));
    expect(checks[0].pass).toBe(false);
  });

  it("fails the IF check for either 'intensity' or 'no_threshold'", () => {
    expect(decouplingChecks(baseActivity({ decoupling_reasons: ["intensity"] }))[1].pass).toBe(false);
    expect(decouplingChecks(baseActivity({ decoupling_reasons: ["no_threshold"] }))[1].pass).toBe(false);
  });

  it("fails the steady check when 'short' is a reason", () => {
    expect(decouplingChecks(baseActivity({ decoupling_reasons: ["short"] }))[2].pass).toBe(false);
  });

  it("fails the coverage check for either hr_coverage or power_coverage", () => {
    expect(decouplingChecks(baseActivity({ decoupling_reasons: ["hr_coverage"] }))[3].pass).toBe(false);
    expect(decouplingChecks(baseActivity({ decoupling_reasons: ["power_coverage"] }))[3].pass).toBe(false);
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
    expect(decouplingChecks(neverComputed).every((c) => c.pass === null)).toBe(true);
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

describe("notScoredReasonText", () => {
  it("formats a too-variable reason with the sport's own VI limit", () => {
    const text = notScoredReasonText(baseActivity({ decoupling_reasons: ["variable"], decoupling_vi: 1.12 }));
    expect(text).toBe("too variable (VI 1.12, limit 1.06)");
  });

  it("formats a too-intense reason", () => {
    const text = notScoredReasonText(baseActivity({ decoupling_reasons: ["intensity"], decoupling_if: 0.91 }));
    expect(text).toBe("too intense (IF 0.91, limit 0.85)");
  });

  it("formats a too-short reason in whole minutes", () => {
    const text = notScoredReasonText(baseActivity({ decoupling_reasons: ["short"], steady_seconds: 2880 }));
    expect(text).toBe("too short (48 min steady, needs 60)");
  });

  it("formats an HR coverage reason", () => {
    const text = notScoredReasonText(baseActivity({ decoupling_reasons: ["hr_coverage"], decoupling_hr_coverage_pct: 72 }));
    expect(text).toBe("HR coverage 72% (needs 90%)");
  });

  it("says 'not yet computed' for an activity that predates this feature, not a false specific reason", () => {
    const text = notScoredReasonText(baseActivity({ decoupling_qualified: false, decoupling_reasons: [] }));
    expect(text).toBe("not yet computed");
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
