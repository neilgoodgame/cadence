import type { RaceBlockWeek } from "../../api/types";
import { buildBlockChart, type BlockChartMetric } from "./raceBlockChart";

export const CHART_METRICS: BlockChartMetric[] = ["tss", "distance", "time"];

export const CHART_METRIC_LABEL: Record<BlockChartMetric, string> = { tss: "TSS", distance: "Distance", time: "Time" };

const CHART_TITLE: Record<BlockChartMetric, string> = {
  tss: "Weekly training load (TSS)",
  distance: "Weekly distance (km)",
  time: "Weekly time (hours)",
};

export interface ChartColors {
  run: string;
  ride: string;
  other: string;
  ink: string;
  ink2: string;
  ink3: string;
  line: string;
  background: string;
}

/** Fixed light palette for the exported chart image, independent of the viewer's live app theme
 * (dark/dim/light) - a downloaded spreadsheet opens on a white Excel sheet, so a theme-matched
 * dark palette would risk unreadable/invisible text depending on which theme was active when the
 * report was generated. */
export const CHART_COLORS: ChartColors = {
  run: "#ec4a26",
  ride: "#3d7fd6",
  other: "#94a3ac",
  ink: "#16212b",
  ink2: "#51606b",
  ink3: "#8a97a1",
  line: "#d8dee2",
  background: "#ffffff",
};

const AXIS_W = 40;
const TOP_PAD = 36;
const BOTTOM_PAD = 24;
export const CHART_WIDTH = 860;
export const CHART_HEIGHT = 320;

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/** A static (non-interactive) weekly bar chart for the race report's Excel export - reuses the
 * same bar-stacking data (raceBlockChart.ts's buildBlockChart, sport "all") as the on-screen
 * TrainingBlockChart's TSS/Distance/Time metric toggle, rendered as a plain SVG string.
 * Deliberately drops the CTL line (per the user's request) and any hover/selection/phase-strip
 * state, since this is a one-shot snapshot rather than an interactive card. */
export function buildTrainingLoadChartSvg(
  weeks: RaceBlockWeek[],
  comparisonWeeks: RaceBlockWeek[] | null,
  comparisonLabel: string | null,
  metric: BlockChartMetric = "tss",
  colors: ChartColors = CHART_COLORS,
  width = CHART_WIDTH,
  height = CHART_HEIGHT,
): string {
  const chart = buildBlockChart(weeks, comparisonWeeks, metric, "all");
  const plotW = width - AXIS_W;
  const plotH = height - TOP_PAD - BOTTOM_PAD;
  const n = Math.max(weeks.length, 1);
  const step = plotW / n;
  const bandwidth = step * 0.75;
  const bandOffset = step * 0.125;
  const yMax = chart.yMax;
  const y = (v: number) => plotH - (v / yMax) * plotH;

  const parts: string[] = [];
  parts.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="Arial, sans-serif">`);
  parts.push(`<rect x="0" y="0" width="${width}" height="${height}" fill="${colors.background}" />`);
  parts.push(`<text x="${AXIS_W}" y="14" font-size="12" font-weight="700" fill="${colors.ink}">${esc(CHART_TITLE[metric])}</text>`);

  const legendItems: { label: string; color: string; dashed?: boolean }[] = [
    { label: "Run", color: colors.run },
    { label: "Ride", color: colors.ride },
    { label: "Other", color: colors.other },
  ];
  const hasComparison = !!comparisonWeeks && comparisonWeeks.length > 0;
  if (hasComparison) legendItems.push({ label: comparisonLabel ?? "Comparison", color: colors.ink3, dashed: true });

  let legendX = width - AXIS_W;
  const legendWidths = legendItems.map((item) => 14 + item.label.length * 6 + 16);
  legendX -= legendWidths.reduce((a, b) => a + b, 0);
  legendItems.forEach((item, i) => {
    if (item.dashed) {
      parts.push(`<rect x="${legendX}" y="22" width="10" height="10" fill="none" stroke="${item.color}" stroke-dasharray="2,2" />`);
    } else {
      parts.push(`<rect x="${legendX}" y="22" width="10" height="10" fill="${item.color}" />`);
    }
    parts.push(`<text x="${legendX + 14}" y="30" font-size="11" fill="${colors.ink2}">${esc(item.label)}</text>`);
    legendX += legendWidths[i];
  });

  parts.push(`<g transform="translate(${AXIS_W},${TOP_PAD})">`);

  const tickCount = 4;
  for (let i = 0; i <= tickCount; i++) {
    const v = (yMax / tickCount) * i;
    const ty = y(v);
    parts.push(`<line x1="0" x2="${plotW}" y1="${ty}" y2="${ty}" stroke="${colors.line}" stroke-dasharray="3,3" />`);
    const tickLabel = metric === "tss" ? String(Math.round(v)) : v.toFixed(1);
    parts.push(`<text x="-8" y="${ty}" text-anchor="end" dominant-baseline="middle" font-size="10" fill="${colors.ink3}">${tickLabel}</text>`);
  }

  chart.bars.forEach((bar, i) => {
    const bx = i * step + bandOffset;
    const comparisonValue = chart.comparisonValues[i];
    if (comparisonValue != null) {
      const cy = y(comparisonValue);
      parts.push(`<rect x="${bx}" y="${cy}" width="${bandwidth}" height="${plotH - cy}" fill="none" stroke="${colors.ink3}" stroke-dasharray="3,3" />`);
    }
    for (const seg of bar.segments) {
      const segColor = seg.sport === "run" ? colors.run : seg.sport === "ride" ? colors.ride : colors.other;
      const yTop = y(seg.to);
      const yBottom = y(seg.from);
      if (bar.isPlanned) {
        parts.push(`<rect x="${bx}" y="${yTop}" width="${bandwidth}" height="${yBottom - yTop}" fill="none" stroke="${segColor}" stroke-dasharray="3,3" />`);
      } else {
        parts.push(`<rect x="${bx}" y="${yTop}" width="${bandwidth}" height="${yBottom - yTop}" fill="${segColor}" />`);
      }
    }
    if (bar.raceValue > 0) {
      const yTop = y(bar.raceValue);
      parts.push(`<rect x="${bx}" y="${yTop}" width="${bandwidth}" height="${plotH - yTop}" fill="${colors.ink}" opacity="0.18" />`);
    }
  });

  parts.push(`<line x1="0" x2="${plotW}" y1="${plotH}" y2="${plotH}" stroke="${colors.ink3}" stroke-width="1.5" />`);
  chart.bars.forEach((bar, i) => {
    const bx = i * step + bandOffset + bandwidth / 2;
    parts.push(`<text x="${bx}" y="${plotH + 16}" text-anchor="middle" font-size="10" fill="${colors.ink3}">${bar.index + 1}</text>`);
  });

  parts.push(`</g>`);
  parts.push(`</svg>`);
  return parts.join("");
}

/** Rasterizes the SVG string into a PNG so it can be embedded as a static image via ExcelJS's
 * `addImage` (which has no native chart-object support). Browser-only (Image/canvas), so it's
 * exercised only from downloadRaceReport, not unit-tested directly - same as that function's own
 * Blob/anchor download trigger below it. */
export async function renderChartPng(svg: string, width: number, height: number, background: string): Promise<ArrayBuffer> {
  const svgBlob = new Blob([svg], { type: "image/svg+xml" });
  const url = URL.createObjectURL(svgBlob);
  try {
    const img = new Image();
    await new Promise<void>((resolve, reject) => {
      img.onload = () => resolve();
      img.onerror = () => reject(new Error("Failed to rasterize chart SVG"));
      img.src = url;
    });
    const scale = 2;
    const canvas = document.createElement("canvas");
    canvas.width = width * scale;
    canvas.height = height * scale;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Canvas 2D context unavailable");
    context.fillStyle = background;
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.scale(scale, scale);
    context.drawImage(img, 0, 0, width, height);
    const blob: Blob = await new Promise((resolve, reject) => {
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("Canvas toBlob failed"))), "image/png");
    });
    return await blob.arrayBuffer();
  } finally {
    URL.revokeObjectURL(url);
  }
}
