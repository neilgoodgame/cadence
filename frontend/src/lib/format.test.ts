import { describe, expect, it } from "vitest";
import { formatDuration, formatKeyMetric, formatPace, parsePace } from "./format";

describe("formatDuration", () => {
  it("formats under an hour as M:SS", () => {
    expect(formatDuration(125)).toBe("2:05");
  });

  it("formats an hour or more as H:MM:SS", () => {
    expect(formatDuration(5449)).toBe("1:30:49");
  });

  it("pads single-digit seconds", () => {
    expect(formatDuration(61)).toBe("1:01");
  });
});

describe("formatPace", () => {
  it("formats seconds-per-km as M:SS /km", () => {
    expect(formatPace(270)).toBe("4:30 /km");
  });

  it("pads single-digit seconds", () => {
    expect(formatPace(245)).toBe("4:05 /km");
  });
});

describe("parsePace", () => {
  it("parses M:SS to seconds per km", () => {
    expect(parsePace("4:30")).toBe(270);
  });

  it("round-trips with formatPace (minus the /km suffix)", () => {
    expect(parsePace("4:05")).toBe(245);
  });

  it("returns null for an unparseable string", () => {
    expect(parsePace("not a pace")).toBeNull();
    expect(parsePace("")).toBeNull();
  });
});

describe("formatKeyMetric", () => {
  it("uses power when available", () => {
    expect(formatKeyMetric({ moving_time: 4520, distance_km: 35.0, avg_power: 231 })).toBe("1:15:20 · 35.0 km · 231 W");
  });

  it("falls back to pace when there's no power", () => {
    expect(formatKeyMetric({ moving_time: 3902, distance_km: 13.0, avg_power: null })).toBe("1:05:02 · 13.0 km · 5:00 /km");
  });

  it("omits the third segment when distance is zero and there's no power", () => {
    expect(formatKeyMetric({ moving_time: 1800, distance_km: 0, avg_power: null })).toBe("30:00 · 0.0 km");
  });
});
