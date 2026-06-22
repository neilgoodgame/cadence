import { describe, expect, it } from "vitest";
import { dateKey, derivedStatus, monthGridDays } from "./calendar";
import type { ScheduledWorkout } from "../api/types";

function entry(overrides: Partial<ScheduledWorkout>): ScheduledWorkout {
  return {
    id: "sch_1",
    workout_id: "wkt_1",
    athlete_id: "usr_1",
    assigned_by: null,
    date: "2026-06-15",
    time_of_day: "",
    status: "planned",
    activity_id: null,
    ...overrides,
  };
}

describe("monthGridDays", () => {
  it("starts on a Monday and ends on a Sunday", () => {
    const days = monthGridDays(2026, 5); // June 2026 - starts on a Monday already
    expect(days[0].getDay()).toBe(1);
    expect(days.at(-1)!.getDay()).toBe(0);
  });

  it("includes the leading/trailing days from adjacent months for a month that doesn't start on Monday", () => {
    // February 2026 starts on a Sunday, so the grid should lead with January's days.
    const days = monthGridDays(2026, 1);
    expect(days[0].getMonth()).toBe(0);
    expect(days.every((_, i) => i === 0 || days[i].getTime() > days[i - 1].getTime())).toBe(true);
  });

  it("produces complete weeks (a multiple of 7 days)", () => {
    const days = monthGridDays(2026, 5);
    expect(days.length % 7).toBe(0);
  });
});

describe("derivedStatus", () => {
  it("leaves completed and missed-irrelevant planned entries alone", () => {
    expect(derivedStatus(entry({ status: "completed", date: "2026-06-01" }), new Date(2026, 5, 15))).toBe("completed");
  });

  it("treats an overdue planned entry as missed without the API ever saying so", () => {
    const overdue = entry({ status: "planned", date: "2026-06-10" });
    expect(derivedStatus(overdue, new Date(2026, 5, 15))).toBe("missed");
  });

  it("does not treat a planned entry today or in the future as missed", () => {
    expect(derivedStatus(entry({ status: "planned", date: "2026-06-15" }), new Date(2026, 5, 15))).toBe("planned");
    expect(derivedStatus(entry({ status: "planned", date: "2026-06-20" }), new Date(2026, 5, 15))).toBe("planned");
  });
});

describe("dateKey", () => {
  it("formats as an ISO date", () => {
    expect(dateKey(new Date(2026, 5, 15))).toBe("2026-06-15");
  });
});
