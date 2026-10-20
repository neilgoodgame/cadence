import type * as ExcelJSType from "exceljs";
import { getRaceSplits, getRaceWeather, listRaceComments } from "../../api/races";
import type {
  Activity,
  Course,
  Race,
  RaceBlock,
  RaceBlockWeek,
  RaceSplit,
  RaceSplits,
  RaceWeather,
  SplitsMode,
} from "../../api/types";
import { formatDuration, formatPace, parseDuration } from "../../lib/format";
import { formatGrade, SECTION_LABELS } from "../../lib/courseSections";
import { splitRowLabel } from "../../lib/splits";
import type { BlockChartMetric, BlockChartSport } from "./raceBlockChart";
import {
  buildTrainingLoadChartSvg,
  chartKey,
  CHART_COLORS,
  CHART_HEIGHT,
  CHART_METRIC_LABEL,
  CHART_METRICS,
  CHART_SPORT_LABEL,
  CHART_SPORTS,
  CHART_WIDTH,
  renderChartPng,
} from "./raceReportChart";

const MODE_LABEL: Record<SplitsMode, string> = { laps: "Laps", "1": "1 km", "5": "5 km", elevation: "Elevation" };

const HEADER_FILL: ExcelJSType.Fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FFEFEFEF" } };

function styleHeaderRow(row: ExcelJSType.Row) {
  row.eachCell((cell) => {
    cell.font = { bold: true };
    cell.fill = HEADER_FILL;
  });
}

function addTable(sheet: ExcelJSType.Worksheet, headers: string[], rows: (string | number | null)[][], startRow = 1) {
  const headerRow = sheet.getRow(startRow);
  headers.forEach((h, i) => (headerRow.getCell(i + 1).value = h));
  styleHeaderRow(headerRow);
  rows.forEach((r, i) => {
    const row = sheet.getRow(startRow + 1 + i);
    r.forEach((v, j) => (row.getCell(j + 1).value = v));
  });
  sheet.columns.forEach((col, i) => {
    const headerLen = headers[i]?.length ?? 10;
    const maxDataLen = rows.reduce((max, r) => Math.max(max, String(r[i] ?? "").length), 0);
    col.width = Math.min(40, Math.max(col.width ?? 0, headerLen + 2, maxDataLen + 2));
  });
}

function kvSheet(sheet: ExcelJSType.Worksheet, pairs: [string, string | number | null][]) {
  pairs.forEach(([k, v], i) => {
    const row = sheet.getRow(i + 1);
    row.getCell(1).value = k;
    row.getCell(1).font = { bold: true };
    row.getCell(2).value = v;
  });
  sheet.getColumn(1).width = 22;
  sheet.getColumn(2).width = 40;
}

function goalPaceLabel(race: Race): string | null {
  const goalS = race.goal_time ? parseDuration(race.goal_time) : null;
  if (goalS == null || !race.distance_km) return null;
  return formatPace(goalS / race.distance_km);
}

function resultPaceLabel(race: Race): string | null {
  const resultS = race.result_time ? parseDuration(race.result_time) : null;
  if (resultS == null || !race.distance_km) return null;
  return formatPace(resultS / race.distance_km);
}

function splitRow(split: RaceSplit, index: number, mode: SplitsMode): (string | number | null)[] {
  return [
    splitRowLabel(split, index, mode),
    Number(split.start_km.toFixed(2)),
    Number(split.end_km.toFixed(2)),
    formatDuration(split.time_s),
    split.pace_s_per_km != null ? formatPace(split.pace_s_per_km) : null,
    split.gap_pace_s_per_km != null ? formatPace(split.gap_pace_s_per_km) : null,
    split.avg_hr,
    split.avg_power,
    split.delta_vs_ref_s_per_km,
    split.delta_power_w,
    split.section_type ? SECTION_LABELS[split.section_type] : null,
    split.gain_m,
    split.loss_m,
    split.avg_grade_pct != null ? formatGrade(split.avg_grade_pct) : null,
  ];
}

const SPLITS_HEADERS = [
  "Split", "Start km", "End km", "Time", "Pace", "GAP pace", "Avg HR", "Avg power (W)",
  "Δ pace (s/km)", "Δ power (W)", "Section", "Gain (m)", "Loss (m)", "Grade",
];

function addSplitsSheet(wb: ExcelJSType.Workbook, mode: SplitsMode, data: RaceSplits) {
  if (data.splits.length === 0) return;
  const sheet = wb.addWorksheet(`Splits - ${MODE_LABEL[mode]}`);
  addTable(sheet, SPLITS_HEADERS, data.splits.map((s, i) => splitRow(s, i, mode)));
}

function weekRow(week: RaceBlockWeek, index: number): (string | number | null)[] {
  return [
    index + 1,
    week.week_start,
    week.tss,
    week.run_km,
    week.run_moving_s != null ? formatDuration(week.run_moving_s) : null,
    week.run_count,
    week.run_avg_pace_s_per_km != null ? formatPace(week.run_avg_pace_s_per_km) : null,
    week.run_long_km,
    week.ride_km,
    week.ride_moving_s != null ? formatDuration(week.ride_moving_s) : null,
    week.ride_count,
    week.ride_avg_power_w,
    week.hours,
    week.ctl != null ? Math.round(week.ctl) : null,
    week.atl != null ? Math.round(week.atl) : null,
    week.planned ? "Planned" : "Actual",
    week.races.map((r) => `${r.priority} · ${r.name}${r.result_s != null ? ` · ${formatDuration(r.result_s)}` : ""}`).join("; "),
  ];
}

const WEEK_HEADERS = [
  "Week", "Week of", "TSS", "Run km", "Run time", "Run sessions", "Run avg pace", "Run long km",
  "Ride km", "Ride time", "Ride sessions", "Ride avg power (W)", "Hours", "CTL", "ATL", "Status", "Races",
];

function addTrainingBlockSheet(wb: ExcelJSType.Workbook, block: RaceBlock) {
  const sheet = wb.addWorksheet("Training block");
  const form = block.race_day_form;
  const summary: [string, string | number | null][] = [
    ["Block", `${block.start} – ${block.end} (${block.weeks.length} weeks)`],
    ["Race-day form (CTL)", Math.round(form.ctl)],
    ["Race-day form (ATL)", form.atl != null ? Math.round(form.atl) : null],
    ["Race-day form (TSB)", form.tsb != null ? Math.round(form.tsb) : null],
    ["Form band", form.band],
    ["Predicted time", block.prediction ? formatDuration(block.prediction.time_s) : null],
    ["Sweat rate (L/h)", `${block.sweat_rate_l_per_h} (${block.sweat_rate_source})`],
  ];
  kvSheet(sheet, summary);
  addTable(sheet, WEEK_HEADERS, block.weeks.map((w, i) => weekRow(w, i)), summary.length + 2);
}

function addTrainingLoadChartSheet(wb: ExcelJSType.Workbook, metric: BlockChartMetric, sport: BlockChartSport, chartImage: ArrayBuffer | null) {
  if (!chartImage) return;
  const name = sport === "all" ? `Chart - ${CHART_METRIC_LABEL[metric]}` : `Chart - ${CHART_METRIC_LABEL[metric]} (${CHART_SPORT_LABEL[sport]})`;
  const sheet = wb.addWorksheet(name);
  const imageId = wb.addImage({ extension: "png", buffer: chartImage });
  sheet.addImage(imageId, { tl: { col: 0, row: 0 }, ext: { width: CHART_WIDTH, height: CHART_HEIGHT } });
}

function addKeySessionsSheet(wb: ExcelJSType.Workbook, block: RaceBlock) {
  if (block.key_sessions.items.length === 0) return;
  const sheet = wb.addWorksheet("Key sessions");
  addTable(
    sheet,
    ["Date", "Name", "Reason", "Sport", "Distance (km)", "TSS"],
    block.key_sessions.items.map((item) => [item.date, item.name, item.reason, item.sport, item.km, item.tss]),
  );
}

function addHistorySheet(wb: ExcelJSType.Workbook, history: Race[], pbS: number | null) {
  if (history.length === 0) return;
  const sheet = wb.addWorksheet("History at distance");
  addTable(
    sheet,
    ["Date", "Name", "Result", "Result pace", "PB"],
    history.map((r) => {
      const s = r.result_time ? parseDuration(r.result_time) : null;
      return [r.date, r.name, s != null ? formatDuration(s) : null, resultPaceLabel(r), pbS != null && s === pbS ? "Yes" : ""];
    }),
  );
}

function addCommentsSheet(wb: ExcelJSType.Workbook, comments: { author_name: string; role: string; week: number | null; text: string; created_at: string }[]) {
  if (comments.length === 0) return;
  const sheet = wb.addWorksheet("Comments");
  addTable(
    sheet,
    ["Date", "Author", "Role", "Week", "Comment"],
    comments.map((c) => [new Date(c.created_at).toLocaleString(), c.author_name, c.role, c.week, c.text]),
  );
}

function addWeatherRows(pairs: [string, string | number | null][], weather: RaceWeather | null) {
  if (!weather || !weather.available) return;
  pairs.push(
    ["Weather forecast", weather.date],
    ["Temp (min/max °C)", `${weather.temperature_min_c ?? "–"} / ${weather.temperature_max_c ?? "–"}`],
    ["Precipitation chance", weather.precipitation_probability_pct != null ? `${weather.precipitation_probability_pct}%` : null],
    ["Max wind (km/h)", weather.wind_speed_max_kmh],
  );
}

function addOverviewSheet(
  wb: ExcelJSType.Workbook,
  race: Race,
  course: Course | null,
  activity: Activity | null,
  block: RaceBlock,
  pbS: number | null,
  weather: RaceWeather | null,
) {
  const sheet = wb.addWorksheet("Overview");
  const resultS = race.result_time ? parseDuration(race.result_time) : null;
  const goalS = race.goal_time ? parseDuration(race.goal_time) : null;
  const pairs: [string, string | number | null][] = [
    ["Name", race.name],
    ["Date", race.date],
    ["Sport", race.sport],
    ["Priority", race.priority],
    ["Distance (km)", race.distance_km],
    ["Goal time", goalS != null ? formatDuration(goalS) : null],
    ["Goal pace", goalPaceLabel(race)],
    ["Target power (W)", race.target_power],
    ["Result time", resultS != null ? formatDuration(resultS) : null],
    ["Result pace", resultPaceLabel(race)],
    ["vs goal", resultS != null && goalS != null ? `${resultS - goalS <= 0 ? "-" : "+"}${formatDuration(Math.abs(resultS - goalS))}` : null],
    ["Personal best", pbS != null ? formatDuration(pbS) : null],
    ["Predicted time", block.prediction ? formatDuration(block.prediction.time_s) : null],
    ["Avg HR", activity?.avg_hr ?? null],
    ["Avg power (W)", activity?.avg_power ?? null],
    ["Avg temp (°C)", activity?.avg_air_temp ?? null],
    ["Course", course?.name ?? null],
    ["Course distance (km)", course?.distance_km ?? null],
    ["Course elevation gain (m)", course?.elevation_gain_m ?? null],
    ["Course elevation loss (m)", course?.elevation_loss_m ?? null],
  ];
  addWeatherRows(pairs, weather);
  pairs.push(
    ["URL", race.url],
    ["Results URL", race.results_url],
    ["Notes", race.notes || null],
  );
  kvSheet(sheet, pairs);
}

export interface RaceReportContext {
  race: Race;
  block: RaceBlock;
  course: Course | null;
  activity: Activity | null;
  pbS: number | null;
  history: Race[];
  /** Mode -> already-fetched splits (e.g. SplitsCard/RaceDetailScreen's own queries) - reused
   * to avoid a duplicate request for whichever mode the athlete already has open. */
  splitsByMode: Partial<Record<SplitsMode, RaceSplits>>;
  showSplits: boolean;
  showWeather: boolean;
  elevationAvailable: boolean;
  /** "{race name} {year}" (or null) for the block's comparison overlay, matching
   * TrainingBlockCard's own label - used as the chart sheet's legend text. */
  comparisonLabel: string | null;
}

export interface ResolvedRaceReportData {
  splitModes: SplitsMode[];
  splitsByMode: Partial<Record<SplitsMode, RaceSplits>>;
  comments: { author_name: string; role: string; week: number | null; text: string; created_at: string }[];
  weather: RaceWeather | null;
  /** Rasterized weekly bar chart (PNG) per (metric, sport) combination, keyed by chartKey() - a
   * combination missing from this map (or the whole map empty) omits that chart sheet entirely.
   * Built by downloadRaceReport since it needs the DOM/canvas, not buildRaceReportWorkbook. */
  chartImages: Partial<Record<string, ArrayBuffer>>;
}

function sanitizeFilename(name: string): string {
  return name.replace(/[/\\?%*:|"<>]/g, "-").trim() || "race";
}

/** Pure workbook assembly - no network, no DOM - so it's directly testable (see
 * raceReport.test.ts) independent of downloadRaceReport's fetch orchestration below. */
export function buildRaceReportWorkbook(
  ExcelJS: typeof ExcelJSType,
  ctx: RaceReportContext,
  resolved: ResolvedRaceReportData,
): ExcelJSType.Workbook {
  const wb = new ExcelJS.Workbook();
  wb.creator = "Cadence";
  wb.created = new Date();

  addOverviewSheet(wb, ctx.race, ctx.course, ctx.activity, ctx.block, ctx.pbS, resolved.weather);
  resolved.splitModes.forEach((m) => {
    const data = resolved.splitsByMode[m];
    if (data) addSplitsSheet(wb, m, data);
  });
  addTrainingBlockSheet(wb, ctx.block);
  CHART_METRICS.forEach((m) =>
    CHART_SPORTS.forEach((s) => addTrainingLoadChartSheet(wb, m, s, resolved.chartImages[chartKey(m, s)] ?? null)),
  );
  addKeySessionsSheet(wb, ctx.block);
  addHistorySheet(wb, ctx.history, ctx.pbS);
  addCommentsSheet(wb, resolved.comments);
  return wb;
}

/** Fetches whatever the page hasn't already loaded (the splits modes other than the
 * currently-active tab, weather, comments), builds the workbook, then triggers a browser
 * download. ExcelJS is dynamically imported so its (fairly large) bundle only loads when this
 * is actually used, not on every race page visit. */
export async function downloadRaceReport(ctx: RaceReportContext): Promise<void> {
  const ExcelJS = await import("exceljs");
  const { race, showSplits, showWeather, elevationAvailable } = ctx;

  const splitModes: SplitsMode[] = showSplits ? ["laps", "1", "5", ...(elevationAvailable ? (["elevation"] as const) : [])] : [];
  const missingModes = splitModes.filter((m) => !ctx.splitsByMode[m]);
  const comparisonWeeks = ctx.block.comparison?.weeks ?? null;
  const chartCombos = CHART_METRICS.flatMap((m) => CHART_SPORTS.map((s) => [m, s] as const));
  const [fetchedSplits, commentsResult, weather, chartImagesList] = await Promise.all([
    Promise.all(missingModes.map((m) => getRaceSplits(race.id, m))),
    listRaceComments(race.id),
    showWeather ? getRaceWeather(race.id) : Promise.resolve(null),
    ctx.block.weeks.length > 0
      ? Promise.all(
          chartCombos.map(([m, s]) =>
            renderChartPng(
              buildTrainingLoadChartSvg(ctx.block.weeks, comparisonWeeks, ctx.comparisonLabel, m, s, CHART_COLORS, CHART_WIDTH, CHART_HEIGHT),
              CHART_WIDTH,
              CHART_HEIGHT,
              CHART_COLORS.background,
            ),
          ),
        )
      : Promise.resolve([]),
  ]);
  const splitsByMode: Partial<Record<SplitsMode, RaceSplits>> = { ...ctx.splitsByMode };
  missingModes.forEach((m, i) => (splitsByMode[m] = fetchedSplits[i]));
  const chartImages: Partial<Record<string, ArrayBuffer>> = {};
  chartImagesList.forEach((img, i) => (chartImages[chartKey(...chartCombos[i])] = img));

  const wb = buildRaceReportWorkbook(ExcelJS, ctx, { splitModes, splitsByMode, comments: commentsResult.data, weather, chartImages });

  const buffer = await wb.xlsx.writeBuffer();
  const blob = new Blob([buffer], { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `${sanitizeFilename(race.name)}-report.xlsx`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
