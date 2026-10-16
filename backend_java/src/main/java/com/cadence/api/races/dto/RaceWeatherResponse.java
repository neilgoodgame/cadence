package com.cadence.api.races.dto;

import java.time.LocalDate;

/** GET /v1/races/{id}/weather - see WeatherService for how this is resolved. {@code available}
 * is false (every other field null) when the race has no course, the course has no resolved
 * start point, the race is outside the forecast window, or the upstream call failed - weather is
 * a nice-to-have, never an error state for the frontend to render. */
public record RaceWeatherResponse(
		boolean available, LocalDate date, Double temperatureMaxC, Double temperatureMinC,
		Integer precipitationProbabilityPct, Double windSpeedMaxKmh) {

	public static RaceWeatherResponse unavailable() {
		return new RaceWeatherResponse(false, null, null, null, null, null);
	}
}
