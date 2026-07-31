package com.cadence.api.activities.calc;

import com.cadence.api.athletes.Zone;
import java.util.List;
import java.util.Map;

/**
 * Edwards' TRIMP: sum over HR zones of (minutes in zone * zone number 1-5). Chosen over
 * Banister's original formula because it needs no resting-HR baseline - it reuses the same
 * HR zone set already computed for hrTSS.
 */
public final class TrimpCalculator {

	public static Double compute(Map<String, Integer> secondsPerZone, List<Zone> zones) {
		if (secondsPerZone == null || secondsPerZone.isEmpty()) {
			return null;
		}
		double trimp = 0;
		int zoneNumber = 1;
		for (Zone zone : zones) {
			int seconds = secondsPerZone.getOrDefault(zone.name(), 0);
			trimp += (seconds / 60.0) * zoneNumber;
			zoneNumber++;
		}
		return Math.round(trimp * 10) / 10.0;
	}

	private TrimpCalculator() {
	}
}
