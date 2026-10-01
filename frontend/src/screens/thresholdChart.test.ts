import { describe, expect, it } from "vitest";
import {
  bestValue,
  clipToRange,
  isImprovement,
  monthTickStep,
  tooltipDays,
  tooltipDeltaText,
  tooltipSourceText,
  type ClippedPoint,
} from "./thresholdChart";

describe("clipToRange", () => {
  const today = new Date("2026-04-25T00:00:00Z");
  const points: ClippedPoint[] = [
    { date: new Date("2025-01-01T00:00:00Z"), value: 240, real: true },
    { date: new Date("2025-08-01T00:00:00Z"), value: 250, real: true },
    { date: new Date("2026-02-01T00:00:00Z"), value: 260, real: true },
  ];

  it("returns the series unchanged for 'all'", () => {
    expect(clipToRange(points, "all", today)).toEqual(points);
  });

  it("clips to the window and carries in the value in effect at the window start", () => {
    // 16 weeks = 112 days back from 2026-04-25 falls inside the 2025-08-01 -> 2026-02-01 step.
    const clipped = clipToRange(points, "16w", today);
    expect(clipped[0].real).toBe(false);
    expect(clipped[0].value).toBe(250);
    expect(clipped.slice(1)).toEqual([points[2]]);
  });

  it("drops the synthetic start point when the window starts before any entry", () => {
    const clipped = clipToRange(points, "1y", new Date("2025-01-10T00:00:00Z"));
    expect(clipped.every((p) => p.real)).toBe(true);
    expect(clipped).toEqual(points);
  });
});

describe("isImprovement", () => {
  it("higher is better for power fields", () => {
    expect(isImprovement("ftp", 260, 250)).toBe(true);
    expect(isImprovement("ftp", 240, 250)).toBe(false);
  });

  it("lower is better for pace - treated inversely", () => {
    expect(isImprovement("threshold_pace", 240, 250)).toBe(true);
    expect(isImprovement("threshold_pace", 260, 250)).toBe(false);
  });

  it("a point with no previous value always counts as an improvement", () => {
    expect(isImprovement("ftp", 250, null)).toBe(true);
    expect(isImprovement("threshold_pace", 250, null)).toBe(true);
  });
});

describe("bestValue", () => {
  it("is the max for power fields", () => {
    expect(bestValue("ftp", [240, 265, 250])).toBe(265);
  });

  it("is the min (fastest) for pace", () => {
    expect(bestValue("threshold_pace", [270, 240, 255])).toBe(240);
  });

  it("is null for an empty series", () => {
    expect(bestValue("ftp", [])).toBeNull();
  });
});

describe("monthTickStep", () => {
  it("is monthly up to 400 days", () => {
    expect(monthTickStep(399)).toBe(1);
    expect(monthTickStep(400)).toBe(1);
  });

  it("switches to quarterly just past 400 days, up to 800", () => {
    expect(monthTickStep(401)).toBe(3);
    expect(monthTickStep(800)).toBe(3);
  });

  it("switches to every 6 months past 800 days", () => {
    expect(monthTickStep(801)).toBe(6);
  });
});

describe("tooltipDeltaText", () => {
  it("is 'First recorded value' with no previous value", () => {
    expect(tooltipDeltaText("ftp", 250, null)).toBe("First recorded value");
  });

  it("shows a signed watt delta with a space before the unit", () => {
    expect(tooltipDeltaText("ftp", 265, 258)).toBe("+7 W vs previous");
    expect(tooltipDeltaText("ftp", 258, 265)).toBe("−7 W vs previous");
  });

  it("shows an arrowed pace delta", () => {
    expect(tooltipDeltaText("threshold_pace", 240, 246)).toBe("▼ 0:06 /km vs previous");
    expect(tooltipDeltaText("threshold_pace", 246, 240)).toBe("▲ 0:06 /km vs previous");
  });
});

describe("tooltipSourceText", () => {
  it("credits a qualifying activity on improvement", () => {
    expect(tooltipSourceText(true, true)).toBe("Set by a qualifying activity");
  });

  it("explains a drop as the previous best aging out", () => {
    expect(tooltipSourceText(true, false)).toBe("Previous best aged out of window");
  });

  it("falls back to manual entry with no source", () => {
    expect(tooltipSourceText(false, true)).toBe("Entered manually");
  });
});

describe("tooltipDays", () => {
  it("counts whole days between a point and the next change (or today)", () => {
    expect(tooltipDays(new Date("2026-01-01T00:00:00Z"), new Date("2026-01-11T00:00:00Z"))).toBe(10);
  });
});
