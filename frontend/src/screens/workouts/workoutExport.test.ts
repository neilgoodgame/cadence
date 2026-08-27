import { describe, expect, it } from "vitest";
import type { LeafStep } from "../../api/types";
import type { Leaf } from "./workoutTree";
import { buildTcx, buildZwo, type ExportThresholds } from "./workoutExport";

function leaf(overrides: Partial<LeafStep> = {}): Leaf {
  return {
    id: "s1",
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

const emptyThresholds: ExportThresholds = { ftp: null, criticalRunPower: null, lthr: null, thresholdPaceSecPerKm: null };

describe("buildTcx power_unit branching", () => {
  it("emits a watts-unit step's stored value directly, ignoring any FTP reference", () => {
    const xml = buildTcx("W", "bike", [leaf({ power_unit: "watts", target_low: 250, target_high: 280 })], { ...emptyThresholds, ftp: 400 });
    expect(xml).toContain("<Value>250</Value>");
    expect(xml).toContain("<Value>280</Value>");
    expect(xml).not.toContain("1000"); // sanity: not accidentally scaled by the unrelated ftp=400
  });

  it("converts a pct_ftp-unit step to watts using the real per-sport reference", () => {
    const xml = buildTcx("P", "bike", [leaf({ power_unit: "pct_ftp", target_low: 90, target_high: 100 })], { ...emptyThresholds, ftp: 250 });
    expect(xml).toContain("<Value>225</Value>");
    expect(xml).toContain("<Value>250</Value>");
  });

  it("a pct_ftp-unit step respects an explicit override over the real reference", () => {
    const xml = buildTcx("P", "bike", [leaf({ power_unit: "pct_ftp", target_low: 100, target_high: 100 })], { ...emptyThresholds, ftp: 250 }, 300);
    expect(xml).toContain("<Value>300</Value>");
  });

  it("is sport-aware: run power uses critical_run_power, not bike ftp", () => {
    const xml = buildTcx("R", "run", [leaf({ power_unit: "pct_ftp", target_low: 100, target_high: 100 })], { ...emptyThresholds, ftp: 999, criticalRunPower: 280 });
    expect(xml).toContain("<Value>280</Value>");
    expect(xml).not.toContain("<Value>999</Value>");
  });
});

describe("buildZwo power_unit branching", () => {
  it("passes a pct_ftp-unit step's percentage straight through by default", () => {
    const xml = buildZwo("P", "bike", [leaf({ power_unit: "pct_ftp", target_low: 90, target_high: 90 })], { ...emptyThresholds, ftp: 250 });
    expect(xml).toContain('PowerLow="0.90"');
  });

  it("converts a watts-unit step to a fraction using the real reference", () => {
    const xml = buildZwo("W", "bike", [leaf({ power_unit: "watts", target_low: 200, target_high: 250 })], { ...emptyThresholds, ftp: 250 });
    expect(xml).toContain('PowerLow="0.80"');
    expect(xml).toContain('PowerHigh="1.00"');
  });

  it("a watts-unit step uses the override over the real reference when both are set", () => {
    const xml = buildZwo("W", "bike", [leaf({ power_unit: "watts", target_low: 225, target_high: 225 })], { ...emptyThresholds, ftp: 250 }, 300);
    expect(xml).toContain('PowerLow="0.75"');
  });

  it("re-bases a pct_ftp-unit step onto the override via the real reference", () => {
    // 90% of a real FTP of 250 = 225W; re-expressed against an override of 300 -> 0.75
    const xml = buildZwo("P", "bike", [leaf({ power_unit: "pct_ftp", target_low: 90, target_high: 90 })], { ...emptyThresholds, ftp: 250 }, 300);
    expect(xml).toContain('PowerLow="0.75"');
  });
});
