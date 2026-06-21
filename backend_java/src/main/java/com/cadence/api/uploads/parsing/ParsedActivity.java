package com.cadence.api.uploads.parsing;

import com.cadence.api.activities.DistanceSource;
import com.cadence.api.activities.Environment;
import com.cadence.api.common.domain.Sport;
import java.time.Instant;
import java.util.List;

/** The uniform shape every format-specific parser (FIT/GPX/TCX) produces. */
public record ParsedActivity(
		Sport sport,
		Environment environment,
		boolean hasGps,
		Instant startDate,
		String source,
		DistanceSource distanceSource,
		List<Sample> samples,
		List<LapSummary> laps) {

	/** One 1 Hz record. {@code t} is the offset in seconds from {@link #startDate}. */
	public record Sample(
			int t, Double lat, Double lng, Double altitude, Double distanceKm,
			Integer heartrate, Integer cadence, Integer power, Double speed) {
	}

	public record LapSummary(int index, int duration, double distanceKm, Integer avgHr, Integer avgPower) {
	}
}
