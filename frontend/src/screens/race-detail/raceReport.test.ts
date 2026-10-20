import * as ExcelJS from "exceljs";
import { describe, expect, it } from "vitest";
import type { Race, RaceBlock, RaceSplits } from "../../api/types";
import { buildRaceReportWorkbook, type RaceReportContext, type ResolvedRaceReportData } from "./raceReport";

function race(overrides: Partial<Race> = {}): Race {
  return {
    id: "race_1",
    athlete_id: "ath_1",
    name: "Tunbridge Wells Half",
    date: "2026-03-15",
    sport: "run",
    priority: "A",
    course_id: null,
    distance_km: 21.1,
    goal_time: "1:35:00",
    result_time: "1:33:12",
    target_power: null,
    activity_id: "act_1",
    url: null,
    results_url: null,
    notes: "",
    key_session_tag_id: null,
    parent_race_id: null,
    block_start: null,
    block_weeks: null,
    ...overrides,
  };
}

function block(overrides: Partial<RaceBlock> = {}): RaceBlock {
  return {
    start: "2026-01-19",
    end: "2026-03-15",
    weeks_requested: 8,
    truncated_by_race_id: null,
    phases: [],
    parent: null,
    weeks: [
      {
        week_start: "2026-01-19",
        tss: 320,
        tss_races: null,
        run_km: 48.5,
        run_moving_s: 15600,
        run_count: 5,
        run_avg_pace_s_per_km: 322,
        run_long_km: 16,
        run_tss: 300,
        ride_km: null,
        ride_moving_s: null,
        ride_count: null,
        ride_avg_power_w: null,
        ride_long_km: null,
        ride_tss: null,
        hours: 4.3,
        sessions: 5,
        ctl: 62,
        atl: 58,
        planned: false,
        races: [],
      },
    ],
    race_day_form: { ctl: 62, atl: 50, tsb: 12, band: "fresh", projected: false, insufficient_data: false },
    comparison: null,
    prediction: { time_s: 5600, from_race_id: "race_0" },
    key_sessions: { total_candidates: 1, items: [{ date: "2026-03-01", name: "Long run", activity_id: "act_2", scheduled_workout_id: null, reason: "long", label: "16 km", km: 16, tss: 110, sport: "run" }] },
    durability: null,
    sweat_rate_l_per_h: 0.8,
    sweat_rate_source: "default",
    ...overrides,
  };
}

function splits(overrides: Partial<RaceSplits> = {}): RaceSplits {
  return {
    has_laps: true,
    ref: "goal",
    ref_pace_s_per_km: 270,
    ref_power: "average",
    ref_power_w: null,
    halves: { first_s: 2800, second_s: 2792 },
    splits: [
      {
        label: "Lap 1",
        start_km: 0,
        end_km: 5,
        time_s: 1350,
        pace_s_per_km: 270,
        gap_pace_s_per_km: null,
        avg_hr: 155,
        avg_power: null,
        delta_vs_ref_s_per_km: -3,
        delta_power_w: null,
        section_type: null,
        gain_m: null,
        loss_m: null,
        avg_grade_pct: null,
      },
    ],
    ...overrides,
  };
}

function ctx(overrides: Partial<RaceReportContext> = {}): RaceReportContext {
  return {
    race: race(),
    block: block(),
    course: null,
    activity: null,
    pbS: 5592,
    history: [race({ id: "race_0", date: "2025-03-16", result_time: "1:34:00" })],
    splitsByMode: {},
    showSplits: true,
    showWeather: false,
    elevationAvailable: false,
    comparisonLabel: null,
    ...overrides,
  };
}

function resolved(overrides: Partial<ResolvedRaceReportData> = {}): ResolvedRaceReportData {
  return {
    splitModes: ["laps", "1", "5"],
    splitsByMode: { laps: splits(), "1": splits(), "5": splits() },
    comments: [{ author_name: "Coach K", role: "coach", week: 3, text: "Great long run this week.", created_at: "2026-02-10T09:00:00Z" }],
    weather: null,
    chartImages: {},
    ...overrides,
  };
}

async function roundTrip(wb: ExcelJS.Workbook): Promise<ExcelJS.Workbook> {
  const buffer = await wb.xlsx.writeBuffer();
  const loaded = new ExcelJS.Workbook();
  await loaded.xlsx.load(buffer);
  return loaded;
}

describe("buildRaceReportWorkbook", () => {
  it("includes an Overview sheet with the race's key facts", async () => {
    const wb = buildRaceReportWorkbook(ExcelJS, ctx(), resolved());
    const loaded = await roundTrip(wb);
    const overview = loaded.getWorksheet("Overview")!;
    const rows = overview.getRows(1, overview.rowCount)!.map((r) => [r.getCell(1).value, r.getCell(2).value]);
    expect(rows).toContainEqual(["Name", "Tunbridge Wells Half"]);
    expect(rows).toContainEqual(["Result time", "1:33:12"]);
    expect(rows).toContainEqual(["Goal time", "1:35:00"]);
  });

  it("adds one sheet per split mode that has data, named after the mode", async () => {
    const wb = buildRaceReportWorkbook(ExcelJS, ctx(), resolved());
    const loaded = await roundTrip(wb);
    expect(loaded.getWorksheet("Splits - Laps")).toBeDefined();
    expect(loaded.getWorksheet("Splits - 1 km")).toBeDefined();
    expect(loaded.getWorksheet("Splits - 5 km")).toBeDefined();

    const laps = loaded.getWorksheet("Splits - Laps")!;
    const header = laps.getRow(1).values as unknown[];
    expect(header).toContain("Split");
    expect(header).toContain("Avg HR");
    const dataRow = laps.getRow(2).values as unknown[];
    expect(dataRow).toContain(155); // avg_hr from the single fixture split
  });

  it("skips a split mode with no splits entirely (e.g. no manual laps)", async () => {
    const wb = buildRaceReportWorkbook(
      ExcelJS,
      ctx(),
      resolved({ splitsByMode: { laps: splits({ splits: [] }), "1": splits(), "5": splits() } }),
    );
    const loaded = await roundTrip(wb);
    expect(loaded.getWorksheet("Splits - Laps")).toBeUndefined();
    expect(loaded.getWorksheet("Splits - 1 km")).toBeDefined();
  });

  it("includes a Training block sheet with the block summary and weekly rows", async () => {
    const wb = buildRaceReportWorkbook(ExcelJS, ctx(), resolved());
    const loaded = await roundTrip(wb);
    const sheet = loaded.getWorksheet("Training block")!;
    const allValues = sheet.getRows(1, sheet.rowCount)!.flatMap((r) => r.values as unknown[]);
    expect(allValues).toContain("Predicted time");
    expect(allValues).toContain("Week");
    expect(allValues).toContain("Week of");
  });

  it("includes a Comments sheet when there are comments", async () => {
    const wb = buildRaceReportWorkbook(ExcelJS, ctx(), resolved());
    const loaded = await roundTrip(wb);
    const sheet = loaded.getWorksheet("Comments")!;
    const allValues = sheet.getRows(1, sheet.rowCount)!.flatMap((r) => r.values as unknown[]);
    expect(allValues).toContain("Coach K");
    expect(allValues).toContain("Great long run this week.");
  });

  it("omits the Comments sheet entirely when there are none", async () => {
    const wb = buildRaceReportWorkbook(ExcelJS, ctx(), resolved({ comments: [] }));
    const loaded = await roundTrip(wb);
    expect(loaded.getWorksheet("Comments")).toBeUndefined();
  });

  it("includes a History at distance sheet, flagging the personal best", async () => {
    // 1:34:00 == 5640s - matches the history fixture race's own result, so it's the PB.
    const wb = buildRaceReportWorkbook(ExcelJS, ctx({ pbS: 5640 }), resolved());
    const loaded = await roundTrip(wb);
    const sheet = loaded.getWorksheet("History at distance")!;
    const row = sheet.getRow(2).values as unknown[];
    expect(row).toContain("1:34:00");
    expect(row).toContain("Yes");
  });

  it("includes a Chart sheet per metric/sport combination with the embedded image when charts were rendered", async () => {
    const chartImages = Object.fromEntries(
      ["tss:all", "tss:run", "tss:ride", "distance:all", "distance:run", "distance:ride", "time:all", "time:run", "time:ride"].map(
        (k) => [k, new ArrayBuffer(16)],
      ),
    );
    const wb = buildRaceReportWorkbook(ExcelJS, ctx(), resolved({ chartImages }));
    const loaded = await roundTrip(wb);
    for (const name of [
      "Chart - TSS", "Chart - TSS (Run)", "Chart - TSS (Ride)",
      "Chart - Distance", "Chart - Distance (Run)", "Chart - Distance (Ride)",
      "Chart - Time", "Chart - Time (Run)", "Chart - Time (Ride)",
    ]) {
      const sheet = loaded.getWorksheet(name)!;
      expect(sheet).toBeDefined();
      expect(sheet.getImages()).toHaveLength(1);
    }
  });

  it("only adds a Chart sheet for metric/sport combinations that have a rendered image", async () => {
    const wb = buildRaceReportWorkbook(ExcelJS, ctx(), resolved({ chartImages: { "tss:all": new ArrayBuffer(16) } }));
    const loaded = await roundTrip(wb);
    expect(loaded.getWorksheet("Chart - TSS")).toBeDefined();
    expect(loaded.getWorksheet("Chart - TSS (Run)")).toBeUndefined();
    expect(loaded.getWorksheet("Chart - Distance")).toBeUndefined();
  });

  it("omits all Chart sheets when no charts were rendered", async () => {
    const wb = buildRaceReportWorkbook(ExcelJS, ctx(), resolved({ chartImages: {} }));
    const loaded = await roundTrip(wb);
    expect(loaded.getWorksheet("Chart - TSS")).toBeUndefined();
    expect(loaded.getWorksheet("Chart - Distance")).toBeUndefined();
    expect(loaded.getWorksheet("Chart - Time")).toBeUndefined();
  });
});
