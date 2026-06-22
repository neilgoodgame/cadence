export type WearStatus = "good" | "soon" | "replace";

export const WEAR_STATUS_COLOR: Record<WearStatus, string> = {
  good: "#2fa66a",
  soon: "#f0a02e",
  replace: "#e0442e",
};

export const WEAR_STATUS_LABEL: Record<WearStatus, string> = {
  good: "Good",
  soon: "Soon",
  replace: "Replace",
};

/** No server-side computed wear state exists (confirmed against both backends) - km/limit_km
 * thresholds taken from the design prototype: >=100% replace, >=85% soon, else good. */
export function wearStatus(km: number, limitKm: number): WearStatus {
  if (limitKm <= 0) return "good";
  const fraction = km / limitKm;
  if (fraction >= 1.0) return "replace";
  if (fraction >= 0.85) return "soon";
  return "good";
}
