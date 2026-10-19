import { describe, expect, it } from "vitest";
import { defaultSplitsMode } from "./splits";

describe("defaultSplitsMode", () => {
  it("prefers laps when the activity has them", () => {
    expect(defaultSplitsMode(true, 42.195)).toBe("laps");
  });

  it("falls back to 1 km for a race of 10 km or less with no laps", () => {
    expect(defaultSplitsMode(false, 5)).toBe("1");
    expect(defaultSplitsMode(false, 10)).toBe("1");
  });

  it("falls back to 5 km for a race over 10 km with no laps", () => {
    expect(defaultSplitsMode(false, 21.0975)).toBe("5");
  });

  it("falls back to 5 km when distance is unknown", () => {
    expect(defaultSplitsMode(false, null)).toBe("5");
  });
});
