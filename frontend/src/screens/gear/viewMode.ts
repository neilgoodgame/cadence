export type GearView = "cards" | "table";

const STORAGE_KEY = "gear.view";

export function loadGearView(): GearView {
  try {
    return localStorage.getItem(STORAGE_KEY) === "table" ? "table" : "cards";
  } catch {
    return "cards";
  }
}

export function saveGearView(view: GearView) {
  try {
    localStorage.setItem(STORAGE_KEY, view);
  } catch {
    // storage unavailable; the choice just won't persist
  }
}
