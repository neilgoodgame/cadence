import type { ZoneSet } from "../api/types";

export interface ZoneTime {
  name: string;
  seconds: number;
  fraction: number;
}

/**
 * Buckets a series of 1 Hz-ish samples into the athlete's zone boundaries and sums time
 * per zone. Zones are %-of-reference bands (Zone.low_pct/high_pct), so each sample's zone
 * is whichever band its value-as-%-of-reference falls into; the last zone's high_pct is
 * effectively open-ended (anything at or above its low_pct counts), matching how the
 * reference threshold itself works (there's no upper limit on effort).
 */
export function bucketIntoZones(samples: (number | null)[], zoneSet: ZoneSet, secondsPerSample = 1): ZoneTime[] {
  const counts = zoneSet.zones.map(() => 0);
  let total = 0;

  for (const sample of samples) {
    if (sample == null) continue;
    const pct = (sample / zoneSet.reference) * 100;
    total += 1;
    for (let i = zoneSet.zones.length - 1; i >= 0; i--) {
      if (pct >= zoneSet.zones[i].low_pct || i === 0) {
        counts[i] += 1;
        break;
      }
    }
  }

  return zoneSet.zones.map((zone, i) => ({
    name: zone.name,
    seconds: counts[i] * secondsPerSample,
    fraction: total > 0 ? counts[i] / total : 0,
  }));
}
