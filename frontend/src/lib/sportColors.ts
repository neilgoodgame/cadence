import type { Sport } from "../api/types";

const SPORT_COLOR_VAR: Record<Sport, string> = {
  bike: "var(--sport-ride)",
  run: "var(--sport-run)",
  swim: "var(--sport-swim)",
  walk: "var(--sport-walk)",
  row: "var(--sport-row)",
  multisport: "var(--sport-multisport)",
  transition: "var(--sport-transition)",
};

const SPORT_COLOR_SOFT_VAR: Record<Sport, string> = {
  bike: "var(--sport-ride-soft)",
  run: "var(--sport-run-soft)",
  swim: "var(--sport-swim-soft)",
  walk: "var(--sport-walk-soft)",
  row: "var(--sport-row-soft)",
  multisport: "var(--sport-multisport-soft)",
  transition: "var(--sport-transition-soft)",
};

const SPORT_LABEL: Record<Sport, string> = {
  bike: "Ride",
  run: "Run",
  swim: "Swim",
  walk: "Walk",
  row: "Row",
  multisport: "Multisport",
  transition: "Transition",
};

export function sportColor(sport: Sport): string {
  return SPORT_COLOR_VAR[sport];
}

export function sportColorSoft(sport: Sport): string {
  return SPORT_COLOR_SOFT_VAR[sport];
}

export function sportLabel(sport: Sport): string {
  return SPORT_LABEL[sport];
}
