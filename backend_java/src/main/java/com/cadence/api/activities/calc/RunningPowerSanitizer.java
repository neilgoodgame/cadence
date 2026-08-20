package com.cadence.api.activities.calc;

import com.cadence.api.common.domain.Sport;
import java.util.List;

/**
 * Drops implausible running-power samples before they reach a calculator. Confirmed against real
 * Stryd FIT files: the footpod occasionally emits a single (rarely two or three consecutive)
 * sample in the thousands of watts - sometimes an isolated spike, sometimes a near-monotonic
 * sequence suggesting a mis-decoded developer field - while pace/cadence and every neighboring
 * sample stay completely ordinary. There's a clean gap between real running power and these
 * spikes (nothing observed between ~400W and the lowest spike, ~1470W), so a fixed ceiling
 * reliably separates the two without risking a real effort.
 *
 * <p>Cycling is untouched: its power comes from the FIT spec's native {@code power} field (a real
 * power meter), not the developer-field fallback third-party running-power footpods use - see
 * FitFileParser - so it isn't exposed to this failure mode, and a fixed ceiling that's safe for
 * running would be too low for a legitimate cycling sprint.
 */
public final class RunningPowerSanitizer {

	/** Nulls out (rather than clips or interpolates) any sample over the ceiling - the same
	 * representation already used for a missing/dropped sensor reading elsewhere in this
	 * pipeline, which every calculator here already treats as zero. */
	public static List<Integer> sanitize(List<Integer> powerSeries, Sport sport, int maxRunningPowerWatts) {
		if (sport != Sport.RUN) {
			return powerSeries;
		}
		return powerSeries.stream()
				.map(p -> p != null && p > maxRunningPowerWatts ? null : p)
				.toList();
	}

	private RunningPowerSanitizer() {
	}
}
