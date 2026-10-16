package com.cadence.api.races;

import com.cadence.api.courses.Course;
import com.cadence.api.courses.CourseRepository;
import com.cadence.api.races.dto.RaceWeatherResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Race-day weather forecast, from Open-Meteo (free, no API key, same no-auth-needed shape as
 * CourseGeocodingService's Nominatim call). Open-Meteo's own forecast horizon is ~16 days, but
 * this is capped at FORECAST_HORIZON_DAYS by product decision - a forecast more than 10 days out
 * is unreliable enough to not be worth showing. Needs a course's resolved start point
 * (Course.startLat/startLng) - a race with no course, or a course with no start point, has
 * nothing to look up and is always "unavailable" rather than an error.
 *
 * <p>Cached in a plain in-process map keyed by race id, same pattern as CourseGeocodingService,
 * but with a short TTL instead of forever - unlike a course's start point, a forecast goes stale
 * and needs re-fetching. Mirrors backend/races/weather.py exactly.
 */
@Service
public class WeatherService {

	private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
	private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
	private static final int FORECAST_HORIZON_DAYS = 10;
	private static final long CACHE_TTL_MS = 3 * 60 * 60 * 1000L;

	private final CourseRepository courseRepository;
	private final RestClient restClient = RestClient.create();
	private final Map<String, CachedForecast> cache = new ConcurrentHashMap<>();

	public WeatherService(CourseRepository courseRepository) {
		this.courseRepository = courseRepository;
	}

	private record CachedForecast(RaceWeatherResponse forecast, long fetchedAtMs) {}

	private record Daily(
			List<String> time,
			@JsonProperty("temperature_2m_max") List<Double> temperatureMaxC,
			@JsonProperty("temperature_2m_min") List<Double> temperatureMinC,
			@JsonProperty("precipitation_probability_max") List<Integer> precipitationProbabilityPct,
			@JsonProperty("wind_speed_10m_max") List<Double> windSpeedMaxKmh) {}

	private record OpenMeteoResponse(Daily daily) {}

	/** Best-effort forecast for a race's day. Never throws - returns
	 * {@link RaceWeatherResponse#unavailable()} for a missing course/start point, a race outside
	 * the forecast window, or any failure calling Open-Meteo.
	 *
	 * <p>{@code @Transactional}, and re-fetches the course fresh rather than trusting
	 * {@code race.getCourse()} - race may have been loaded by a plain (non-transactional)
	 * repository call in the caller, in a session this method's own transaction doesn't
	 * reattach it to. {@code .getId()} alone is always safe off a lazy proxy regardless;
	 * touching getStartLat()/getStartLng() is not. See backend_java's own CLAUDE.md "JPA entity's
	 * lazy association" gotcha. */
	@Transactional(readOnly = true)
	public RaceWeatherResponse forecastFor(Race race) {
		if (race.getCourse() == null) {
			return RaceWeatherResponse.unavailable();
		}
		Course course = courseRepository.findById(race.getCourse().getId()).orElse(null);
		if (course == null || course.getStartLat() == null || course.getStartLng() == null) {
			return RaceWeatherResponse.unavailable();
		}
		long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), race.getDate());
		if (daysUntil < 0 || daysUntil > FORECAST_HORIZON_DAYS) {
			return RaceWeatherResponse.unavailable();
		}

		CachedForecast cached = cache.get(race.getId());
		if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs() < CACHE_TTL_MS) {
			return cached.forecast();
		}

		RaceWeatherResponse forecast = fetch(course.getStartLat(), course.getStartLng(), race.getDate());
		cache.put(race.getId(), new CachedForecast(forecast, System.currentTimeMillis()));
		return forecast;
	}

	private RaceWeatherResponse fetch(double lat, double lng, LocalDate date) {
		try {
			String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
			OpenMeteoResponse response = restClient.get()
					.uri(FORECAST_URL
							+ "?latitude={lat}&longitude={lng}&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max&timezone=auto&start_date={start}&end_date={end}",
							lat, lng, dateStr, dateStr)
					.retrieve()
					.body(OpenMeteoResponse.class);
			if (response == null || response.daily() == null || response.daily().time().isEmpty()) {
				return RaceWeatherResponse.unavailable();
			}
			Daily d = response.daily();
			return new RaceWeatherResponse(true, date,
					d.temperatureMaxC().get(0), d.temperatureMinC().get(0),
					d.precipitationProbabilityPct().get(0), d.windSpeedMaxKmh().get(0));
		}
		catch (Exception e) {
			log.warn("Weather forecast failed for ({}, {})", lat, lng, e);
			return RaceWeatherResponse.unavailable();
		}
	}
}
