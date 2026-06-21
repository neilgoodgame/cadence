export type Theme = "teal" | "violet" | "day";

const THEME_KEY = "cadence.theme";
const DEFAULT_THEME: Theme = "teal";

export function getStoredTheme(): Theme {
  const stored = localStorage.getItem(THEME_KEY);
  return stored === "teal" || stored === "violet" || stored === "day" ? stored : DEFAULT_THEME;
}

export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
  localStorage.setItem(THEME_KEY, theme);
}
