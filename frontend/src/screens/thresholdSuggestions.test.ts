import { describe, expect, it } from "vitest";
import {
  acceptedDoneText,
  bannerEligible,
  formatFieldValue,
  pickBannerSuggestion,
  raceWillRefreshNote,
  sortSuggestions,
  suggestionDetail,
  suggestionMeta,
  suggestionTitle,
  warningLeadDays,
} from "./thresholdSuggestions";
import type { ThresholdSuggestion } from "../api/types";

function rejected(overrides: Partial<ThresholdSuggestion> = {}): ThresholdSuggestion {
  return {
    id: "ftp:rejected:act_1",
    field: "ftp",
    kind: "rejected",
    current: 265,
    proposed: 297,
    delta_pct: 12.1,
    activity_id: "act_1",
    activity_date: "2026-07-21",
    implied_from: { window: "20min", value: 313 },
    ...overrides,
  };
}

function upcomingDrop(overrides: Partial<ThresholdSuggestion> = {}): ThresholdSuggestion {
  return {
    id: "threshold_pace:upcoming_drop:act_2:2026-08-10",
    field: "threshold_pace",
    kind: "upcoming_drop",
    current: "4:00",
    proposed: "4:07",
    delta_pct: 2.9,
    activity_id: "act_2",
    activity_date: "2026-04-18",
    expiry_date: "2026-08-10",
    days_left: 12,
    ...overrides,
  };
}

function raceWillRefresh(overrides: Partial<ThresholdSuggestion> = {}): ThresholdSuggestion {
  return {
    id: "critical_run_power:race_will_refresh:race_1",
    field: "critical_run_power",
    kind: "race_will_refresh",
    expiry_date: "2026-08-10",
    days_left: 12,
    race: { id: "race_1", name: "City 10K", date: "2026-08-02" },
    ...overrides,
  };
}

describe("bannerEligible", () => {
  it("is always eligible for rejected", () => {
    expect(bannerEligible(rejected())).toBe(true);
  });

  it("is eligible for upcoming_drop at exactly 10 days left", () => {
    expect(bannerEligible(upcomingDrop({ days_left: 10 }))).toBe(true);
  });

  it("is not eligible for upcoming_drop at 11 days left", () => {
    expect(bannerEligible(upcomingDrop({ days_left: 11 }))).toBe(false);
  });

  it("is never eligible for race_will_refresh", () => {
    expect(bannerEligible(raceWillRefresh())).toBe(false);
  });
});

describe("sortSuggestions", () => {
  it("puts every rejected suggestion before upcoming_drop ones", () => {
    const drop = upcomingDrop({ id: "a", days_left: 1 });
    const rej = rejected({ id: "b" });
    expect(sortSuggestions([drop, rej]).map((s) => s.id)).toEqual(["b", "a"]);
  });

  it("orders upcoming_drop suggestions by days_left ascending", () => {
    const far = upcomingDrop({ id: "far", days_left: 9 });
    const near = upcomingDrop({ id: "near", days_left: 2 });
    expect(sortSuggestions([far, near]).map((s) => s.id)).toEqual(["near", "far"]);
  });
});

describe("pickBannerSuggestion", () => {
  it("picks the first eligible suggestion in sort order, skipping ineligible ones", () => {
    const farDrop = upcomingDrop({ id: "far", days_left: 20 });
    const rej = rejected({ id: "rej" });
    const pick = pickBannerSuggestion([farDrop, rej]);
    expect(pick?.current.id).toBe("rej");
  });

  it("returns null when nothing is eligible", () => {
    expect(pickBannerSuggestion([upcomingDrop({ days_left: 20 }), raceWillRefresh()])).toBeNull();
  });

  it("counts every other pending suggestion, any eligibility, for the +N more link", () => {
    const rej = rejected({ id: "rej" });
    const farDrop = upcomingDrop({ id: "far", field: "critical_run_power", days_left: 20 });
    const pick = pickBannerSuggestion([rej, farDrop]);
    expect(pick?.moreCount).toBe(1);
    expect(pick?.moreField).toBe("critical_run_power");
  });
});

describe("formatFieldValue", () => {
  it("rounds and appends W for power fields", () => {
    expect(formatFieldValue("ftp", 296.6)).toBe("297W");
  });

  it("converts raw pace seconds to M:SS/km", () => {
    expect(formatFieldValue("threshold_pace", 247)).toBe("4:07/km");
  });

  it("passes an already-formatted pace string through unchanged", () => {
    expect(formatFieldValue("threshold_pace", "4:00")).toBe("4:00/km");
  });
});

describe("suggestionTitle", () => {
  it("builds the rejected title", () => {
    expect(suggestionTitle(rejected())).toBe("Accept FTP 297W?");
  });

  it("builds the upcoming_drop title for a power field (drops to)", () => {
    expect(suggestionTitle(upcomingDrop({ field: "ftp", proposed: 258, expiry_date: "2026-08-10" }))).toBe(
      "FTP drops to 258W on 10 Aug",
    );
  });

  it("builds the upcoming_drop title for pace (slips to)", () => {
    expect(suggestionTitle(upcomingDrop())).toBe("Threshold pace slips to 4:07/km on 10 Aug");
  });

  it("falls back to 'goes stale' when there is no successor", () => {
    expect(suggestionTitle(upcomingDrop({ proposed: null }))).toBe("Threshold pace goes stale on 10 Aug");
  });
});

describe("suggestionDetail", () => {
  it("includes the implies clause when the raw effort differs from the applied value", () => {
    const detail = suggestionDetail(rejected(), 10);
    expect(detail).toContain("A 20-min effort of 313W on");
    expect(detail).toContain("implies 297W (+12.1%)");
    expect(detail).toContain("±10% sanity range");
  });

  it("omits the implies clause when the raw effort equals the applied value (e.g. sixty_min_direct)", () => {
    const s = rejected({ implied_from: { window: "60min", value: 297 } });
    const detail = suggestionDetail(s, 10);
    expect(detail).toContain("A 60-min effort of 297W on");
    expect(detail).not.toContain("implies");
  });

  it("builds the upcoming_drop detail and offers a race for run fields", () => {
    const detail = suggestionDetail(upcomingDrop(), 10);
    expect(detail).toContain("ages out of your trailing window in 12 days");
    expect(detail).toContain("or a 5K–10K race");
  });

  it("does not offer a race for ftp", () => {
    const detail = suggestionDetail(upcomingDrop({ field: "ftp", current: 265 }), 10);
    expect(detail).not.toContain("race");
  });
});

describe("suggestionMeta", () => {
  it("includes the delta clause when there is a successor", () => {
    expect(suggestionMeta(upcomingDrop(), 21)).toBe("Expires in 12 days · −7 s/km (2.9%) · warning lead time 21 days");
  });

  it("omits the delta clause when there is no successor", () => {
    expect(suggestionMeta(upcomingDrop({ proposed: null, delta_pct: null }), 21)).toBe(
      "Expires in 12 days · warning lead time 21 days",
    );
  });

  it("returns null for a rejected suggestion", () => {
    expect(suggestionMeta(rejected(), 21)).toBeNull();
  });
});

describe("raceWillRefreshNote", () => {
  it("builds the quiet Current-zones note", () => {
    expect(raceWillRefreshNote(raceWillRefresh())).toBe(
      "Your City 10K on 2 Aug should refresh this before it expires on 10 Aug.",
    );
  });
});

describe("acceptedDoneText", () => {
  it("builds the post-accept confirmation", () => {
    expect(acceptedDoneText("ftp", 297, "2026-07-21")).toBe("FTP set to 297W — recorded from 21 Jul.");
  });
});

describe("warningLeadDays", () => {
  it("uses the setting directly for a window of 84 days or more", () => {
    expect(warningLeadDays(112, 21)).toBe(21);
    expect(warningLeadDays(84, 28)).toBe(28);
  });

  it("scales down to a quarter of the window for a shorter window", () => {
    expect(warningLeadDays(56, 21)).toBe(14);
  });

  it("passes 0 (off) straight through for a window of 84 days or more", () => {
    // The "0 = never suggest" rule is enforced by the backend's upcoming_drop detection
    // itself, not by this scaling formula - a short window always overrides the raw setting
    // with a quarter of the window, 0 included, matching threshold_history.py's own
    // warning_lead_days.
    expect(warningLeadDays(112, 0)).toBe(0);
  });
});
