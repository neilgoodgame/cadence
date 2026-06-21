import type { Sport } from "../api/types";

const SPORT_COLOR_VAR: Record<Sport, string> = {
  bike: "var(--sport-ride)",
  run: "var(--sport-run)",
  swim: "var(--sport-swim)",
  walk: "var(--sport-walk)",
};

const SPORT_LABEL: Record<Sport, string> = {
  bike: "Ride",
  run: "Run",
  swim: "Swim",
  walk: "Walk",
};

export function sportColor(sport: Sport): string {
  return SPORT_COLOR_VAR[sport];
}

export function sportLabel(sport: Sport): string {
  return SPORT_LABEL[sport];
}
