import { describe, expect, it } from "vitest";
import type { RecentTopEffort } from "../api/types";
import {
  effortLabel,
  formatBestEffortValue,
  groupRecentEffortsByActivity,
  qualifyingRecentEfforts,
  recentTopEffortHeadline,
  windowLabel,
} from "./bestEfforts";

function entry(overrides: Partial<RecentTopEffort> = {}): RecentTopEffort {
  return {
    activity_id: "act_1",
    date: "2026-07-28",
    sport: "run",
    kind: "running_power",
    window: "5min",
    value: 342,
    unit: "w",
    ranks: { "16w": null, "1y": null, all: null },
    ...overrides,
  };
}

describe("recentTopEffortHeadline", () => {
  it("prefers the widest qualifying period - all-time #3 beats 16-week #1", () => {
    const hl = recentTopEffortHeadline(entry({ ranks: { "16w": 1, "1y": 5, all: 3 } }));
    expect(hl).toMatchObject({ key: "all", rank: 3 });
  });

  it("falls back to a narrower period when wider ones don't qualify", () => {
    const hl = recentTopEffortHeadline(entry({ ranks: { "16w": 2, "1y": null, all: null } }));
    expect(hl).toMatchObject({ key: "16w", rank: 2 });
  });

  it("is null when no period ranks <= 3", () => {
    const hl = recentTopEffortHeadline(entry({ ranks: { "16w": 4, "1y": 8, all: 20 } }));
    expect(hl).toBeNull();
  });
});

describe("qualifyingRecentEfforts", () => {
  it("excludes entries with no qualifying period", () => {
    const list = qualifyingRecentEfforts([entry({ ranks: { "16w": 4, "1y": null, all: null } })]);
    expect(list).toHaveLength(0);
  });

  it("sorts by headline width descending, then rank ascending, then date descending", () => {
    const sixteenWeekRank1 = entry({ activity_id: "a", date: "2026-07-29", ranks: { "16w": 1, "1y": null, all: null } });
    const allTimeRank3 = entry({ activity_id: "b", date: "2026-07-20", ranks: { "16w": null, "1y": null, all: 3 } });
    const allTimeRank1Older = entry({ activity_id: "c", date: "2026-07-15", ranks: { "16w": null, "1y": null, all: 1 } });
    const allTimeRank1Newer = entry({ activity_id: "d", date: "2026-07-25", ranks: { "16w": null, "1y": null, all: 1 } });

    const sorted = qualifyingRecentEfforts([sixteenWeekRank1, allTimeRank3, allTimeRank1Older, allTimeRank1Newer]);

    // Both all-time #1s (widest, best rank) come first, newer date first; all-time #3 next
    // (same width, worse rank); the 16-week #1 last (narrowest width, despite the best rank).
    expect(sorted.map((e) => e.activity_id)).toEqual(["d", "c", "b", "a"]);
  });
});

describe("groupRecentEffortsByActivity", () => {
  it("groups by activity, preserving first-seen order from the already-sorted input", () => {
    const list = qualifyingRecentEfforts([
      entry({ activity_id: "a", kind: "running_power", window: "5min", ranks: { "16w": 2, "1y": null, all: null } }),
      entry({ activity_id: "b", kind: "cycling_power", window: "20min", ranks: { "16w": null, "1y": null, all: 1 } }),
      entry({ activity_id: "a", kind: "running_pace", window: "10km", ranks: { "16w": 3, "1y": null, all: null } }),
    ]);

    const groups = groupRecentEffortsByActivity(list);

    // "b" (all-time #1, widest) outranks "a" (16-week only), so it groups first despite
    // appearing second in the unsorted input.
    expect(groups.map((g) => g.activityId)).toEqual(["b", "a"]);
    expect(groups.find((g) => g.activityId === "a")?.items).toHaveLength(2);
  });
});

describe("windowLabel", () => {
  it("maps duration windows", () => {
    expect(windowLabel("5min")).toBe("5 min");
    expect(windowLabel("60min")).toBe("60 min");
  });

  it("maps distance windows, including the special-cased marathon labels", () => {
    expect(windowLabel("half_marathon")).toBe("Half marathon");
    expect(windowLabel("10mile")).toBe("10 mile");
  });

  it("falls back to the raw value for anything unmapped", () => {
    expect(windowLabel("bogus")).toBe("bogus");
  });
});

describe("effortLabel", () => {
  it("appends the lowercased family for Power/Pace/Heart rate metrics", () => {
    expect(effortLabel("Power", "5 min")).toBe("5 min power");
    expect(effortLabel("Heart rate", "20 min")).toBe("20 min heart rate");
  });

  it("uses the metric alone for Distance/Elevation", () => {
    expect(effortLabel("Distance", "Longest run")).toBe("Longest run");
    expect(effortLabel("Elevation", "Biggest climb")).toBe("Biggest climb");
  });
});

describe("formatBestEffortValue", () => {
  it("formats pace as elapsed time for the window's distance", () => {
    // 299 sec/km over 10km = 2990s = 49:50
    expect(formatBestEffortValue("running_pace", "10km", 299)).toBe("49:50");
  });

  it("formats power in watts", () => {
    expect(formatBestEffortValue("running_power", "5min", 341.7)).toBe("342 W");
  });

  it("formats heart rate in bpm", () => {
    expect(formatBestEffortValue("running_hr", "20min", 175.6)).toBe("176 bpm");
  });
});
