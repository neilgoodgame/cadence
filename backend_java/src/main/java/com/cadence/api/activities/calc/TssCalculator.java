package com.cadence.api.activities.calc;

import com.cadence.api.athletes.Zone;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Training Stress Score: power-based when normalized power and a threshold are available,
 * otherwise a coarse heart-rate-zone-weighted fallback (no power-equivalent intensity factor
 * exists without a power meter, so each zone is weighted by its %-of-threshold midpoint instead).
 */
public final class TssCalculator {

	public static Integer powerBased(Double normPower, Integer thresholdPower, int movingTimeSeconds) {
		if (normPower == null || normPower <= 0 || thresholdPower == null || thresholdPower <= 0) {
			return null;
		}
		double intensity = normPower / thresholdPower;
		double tss = (movingTimeSeconds * normPower * intensity) / (thresholdPower * 3600.0) * 100;
		return (int) Math.round(tss);
	}

	public static int hrBased(Map<String, Integer> secondsPerZone, List<Zone> zones) {
		if (secondsPerZone == null || secondsPerZone.isEmpty()) {
			return 0;
		}
		double tss = 0;
		for (Zone zone : zones) {
			int seconds = secondsPerZone.getOrDefault(zone.name(), 0);
			double midpointPct = (zone.lowPct() + zone.highPct()) / 2.0;
			tss += (seconds / 3600.0) * midpointPct;
		}
		return (int) Math.round(tss);
	}

	/** Buckets each heart-rate sample into the matching zone (first one its %-of-threshold falls within). */
	public static Map<String, Integer> secondsPerZone(List<Integer> heartrateSeries, List<Zone> zones, Double threshold) {
		if (threshold == null || threshold <= 0) {
			return null;
		}
		Map<String, Integer> result = new LinkedHashMap<>();
		for (Zone z : zones) {
			result.put(z.name(), 0);
		}
		for (Integer hr : heartrateSeries) {
			if (hr == null) {
				continue;
			}
			double pct = hr / threshold * 100;
			for (Zone z : zones) {
				if (pct >= z.lowPct() && pct <= z.highPct()) {
					result.merge(z.name(), 1, Integer::sum);
					break;
				}
			}
		}
		return result;
	}

	private TssCalculator() {
	}
}
