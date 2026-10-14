import type { CourseSection, CourseSectionSettings, SectionType } from "../api/types";

/** Steepest-down first, so legends and summaries read left to right like a gradient. */
export const SECTION_TYPES: SectionType[] = ["steep_descent", "descent", "flat", "climb", "steep_climb"];

export const SECTION_LABELS: Record<SectionType, string> = {
  steep_descent: "Steep descent",
  descent: "Descent",
  flat: "Flat",
  climb: "Climb",
  steep_climb: "Steep climb",
};

/** CSS custom properties from styles/themes.css's constant data-viz palette. */
export const SECTION_COLORS: Record<SectionType, string> = {
  steep_descent: "var(--grade-steep-descent)",
  descent: "var(--grade-descent)",
  flat: "var(--grade-flat)",
  climb: "var(--grade-climb)",
  steep_climb: "var(--grade-steep-climb)",
};

export const DEFAULT_SECTION_SETTINGS: CourseSectionSettings = {
  algorithm: "douglas_peucker",
  flat_max_pct: 1.0,
  steep_min_pct: 4.0,
  fit_tolerance_m: 2.0,
  min_section_m: 250,
  keep_min_rise_m: 5,
  spike_window_m: 30,
  smoothing_window_m: 100,
  resample_step_m: 10,
};

export type NumericSettingKey = Exclude<keyof CourseSectionSettings, "algorithm">;

export interface SettingField {
  key: NumericSettingKey;
  label: string;
  help: string;
  unit: string;
  min: number;
  max: number;
  step: number;
  advanced: boolean;
}

/** Same ranges as the backend's SectionSettings.validation_error - the server is still the
 * one that enforces them. */
export const SETTING_FIELDS: SettingField[] = [
  { key: "flat_max_pct", label: "Flat band", help: "Average grade within ± this counts as flat.", unit: "±%", min: 0.5, max: 3, step: 0.1, advanced: false },
  { key: "steep_min_pct", label: "Steep threshold", help: "Average grade beyond ± this counts as steep.", unit: "±%", min: 2, max: 10, step: 0.5, advanced: false },
  { key: "fit_tolerance_m", label: "Fit tolerance", help: "How far a section's straight line may stray from the smoothed elevation. Lower finds smaller hills.", unit: "m", min: 0.5, max: 10, step: 0.5, advanced: false },
  { key: "min_section_m", label: "Minimum section length", help: "Shorter sections fold into a neighbour…", unit: "m", min: 50, max: 1000, step: 10, advanced: false },
  { key: "keep_min_rise_m", label: "…unless they rise or drop at least", help: "Keeps short, sharp hills.", unit: "m", min: 1, max: 20, step: 1, advanced: false },
  { key: "spike_window_m", label: "Spike removal window", help: "Running median that removes elevation spikes. 0 turns it off.", unit: "m", min: 0, max: 100, step: 10, advanced: true },
  { key: "smoothing_window_m", label: "Smoothing window", help: "Running mean applied after spike removal.", unit: "m", min: 20, max: 300, step: 10, advanced: true },
  { key: "resample_step_m", label: "Resample step", help: "Elevation is resampled every this many metres first.", unit: "m", min: 10, max: 50, step: 5, advanced: true },
];

/** The first out-of-range field's message, or null - lets the form explain a problem before
 * saving instead of only showing the server's 400. */
export function settingsError(settings: CourseSectionSettings): string | null {
  for (const f of SETTING_FIELDS) {
    const v = settings[f.key];
    if (!Number.isFinite(v) || v < f.min || v > f.max) {
      const unit = f.unit.replace("±", "");
      return `${f.label} must be between ${f.min} and ${f.max}${unit === "%" ? "%" : ` ${unit}`}.`;
    }
  }
  if (settings.steep_min_pct <= settings.flat_max_pct) {
    return "Steep threshold must be greater than the flat band.";
  }
  return null;
}

export interface SectionSummary {
  flatKm: number;
  climbingKm: number;
  descendingKm: number;
  climbs: number;
}

export function summarizeSections(sections: CourseSection[]): SectionSummary {
  let flat = 0;
  let up = 0;
  let down = 0;
  let climbs = 0;
  for (const s of sections) {
    if (s.type === "flat") {
      flat += s.length_m;
    } else if (s.type === "climb" || s.type === "steep_climb") {
      up += s.length_m;
      climbs += 1;
    } else {
      down += s.length_m;
    }
  }
  return { flatKm: flat / 1000, climbingKm: up / 1000, descendingKm: down / 1000, climbs };
}

/** The elevation_profile points inside [startKm, endKm], with each end interpolated so
 * neighbouring sections' fills meet exactly instead of leaving gaps between profile points. */
export function profileSlice(profile: [number, number][], startKm: number, endKm: number): [number, number][] {
  if (profile.length < 2) return [];
  const at = (km: number): number => {
    if (km <= profile[0][0]) return profile[0][1];
    for (let i = 1; i < profile.length; i++) {
      const [x1, y1] = profile[i];
      if (km <= x1) {
        const [x0, y0] = profile[i - 1];
        return x1 === x0 ? y1 : y0 + ((y1 - y0) * (km - x0)) / (x1 - x0);
      }
    }
    return profile[profile.length - 1][1];
  };
  const inside = profile.filter(([km]) => km > startKm && km < endKm);
  return [[startKm, at(startKm)], ...inside, [endKm, at(endKm)]];
}

export function formatGrade(pct: number): string {
  const sign = pct > 0 ? "+" : pct < 0 ? "−" : "";
  return `${sign}${Math.abs(pct).toFixed(1)}%`;
}
