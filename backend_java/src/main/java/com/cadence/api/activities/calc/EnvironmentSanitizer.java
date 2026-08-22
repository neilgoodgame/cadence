package com.cadence.api.activities.calc;

import java.util.ArrayList;
import java.util.List;

/**
 * Nulls out a run's air-temperature/humidity samples together wherever humidity reads exactly
 * 0% - confirmed against real data as a Stryd ambient-sensor pairing-failure fallback, not a
 * real reading: 0% relative humidity essentially never occurs outdoors (even deserts rarely go
 * that low), and every affected activity showed flat 0.0°C/0% with zero variance for its entire
 * duration, not a plausible weather reading that happens to land on exactly zero. Found live: 25
 * of 572 activities with any Stryd environment data affected, scattered across an otherwise-
 * normal 18-month range - an intermittent sensor/pairing failure, not a firmware-version cutoff.
 *
 * <p>Air temp is dropped alongside humidity (not checked on its own) because 0°C alone is a
 * physically plausible cold-weather reading - it's only suspicious paired with an impossible
 * humidity from the same failed sensor read.
 */
public final class EnvironmentSanitizer {

	public static List<Double> sanitizeAirTemp(List<Double> airTempSeries, List<Integer> humiditySeries) {
		List<Double> result = new ArrayList<>(airTempSeries.size());
		for (int i = 0; i < airTempSeries.size(); i++) {
			Integer humidity = i < humiditySeries.size() ? humiditySeries.get(i) : null;
			result.add(isFallbackZero(humidity) ? null : airTempSeries.get(i));
		}
		return result;
	}

	public static List<Integer> sanitizeHumidity(List<Integer> humiditySeries) {
		return humiditySeries.stream().map(h -> isFallbackZero(h) ? null : h).toList();
	}

	private static boolean isFallbackZero(Integer humidity) {
		return humidity != null && humidity == 0;
	}

	private EnvironmentSanitizer() {
	}
}
