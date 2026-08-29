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
		// Recording device from the file's metadata (FIT file_id), e.g. "Zwift" or
		// "Garmin Edge 830". FIT-only (no GPX/TCX equivalent), so "" from those parsers.
		String device,
		DistanceSource distanceSource,
		List<Sample> samples,
		List<LapSummary> laps,
		// Garmin's Firstbeat-derived training load, from a FIT session message - FIT-only
		// (no GPX/TCX equivalent), so always null from those parsers.
		Double aerobicTrainingEffect,
		Double anaerobicTrainingEffect) {

	/**
	 * One 1 Hz record. {@code t} is the offset in seconds from {@link #startDate}.
	 * {@code airTemp}/{@code humidity} come from a Stryd footpod's developer fields;
	 * {@code coreTemp}/{@code skinTemp}/{@code heatStrain} from a CORE body-temperature
	 * sensor's - both are FIT-only (no GPX/TCX equivalent), so always null from those parsers.
	 */
	public record Sample(
			int t, Double lat, Double lng, Double altitude, Double distanceKm,
			Integer heartrate, Integer cadence, Integer power, Double speed,
			Double airTemp, Integer humidity, Double coreTemp, Double skinTemp, Double heatStrain,
			// Transient - consumed by ComputeDerivedStatsTasklet to compute the activity-level
			// avgLeftBalancePct, not persisted per-sample onto Record (the mockup only shows
			// one aggregate L/R split, not a stream). FIT-only, always null from GPX/TCX.
			Double leftBalancePct,
			// A run can carry both a native-power reading (`power` above, e.g. Garmin Running
			// Power) and a Stryd footpod's developer-field "Power" at once - which one the
			// athlete actually trusts is a per-athlete preference this parser layer has no
			// access to, so both are kept distinct through parsing and resolved downstream (see
			// ParseFileTasklet's power-source resolution). FIT-only, always null from GPX/TCX -
			// those formats have a single power field with no such ambiguity.
			Integer powerStryd) {
	}

	public record LapSummary(int index, int duration, double distanceKm, Integer avgHr, Integer avgPower) {
	}
}
