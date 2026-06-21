import { describe, expect, it } from "vitest";
import { formatDuration, formatPace } from "./format";

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
