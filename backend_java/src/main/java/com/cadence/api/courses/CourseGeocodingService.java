package com.cadence.api.courses;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Reverse geocoding for a course's start point. Nothing in this codebase did this before -
 * Nominatim (OpenStreetMap) is free and needs no API key, at the cost of a 1 req/s rate limit
 * and a required User-Agent, both respected here. Cached in a plain in-process map (this
 * codebase has no caching library - see ThresholdSuggestionService for the established
 * alternative, a dedicated invalidate()-on-write service, which doesn't fit here since a
 * course's start point never moves and so never needs invalidating) since a course's start
 * point never moves. Mirrors the Python backend's courses/geocoding.py exactly.
 */
@Service
public class CourseGeocodingService {

	private static final Logger log = LoggerFactory.getLogger(CourseGeocodingService.class);
	private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse";
	private static final String USER_AGENT = "Cadence/1.0 (training log; contact via app)";

	private final RestClient restClient = RestClient.create();
	private final Map<String, String> cache = new ConcurrentHashMap<>();

	private record Address(String city, String town, String village, String suburb, String county, String country) {}

	private record NominatimResponse(Address address) {}

	/** Best-effort place name for a start point, e.g. "Valencia, Spain". Returns "" on any
	 * failure (network, rate limit, no address match) - locationName is a user-editable
	 * nice-to-have, never something course creation should fail over. */
	public String reverseGeocode(double lat, double lng) {
		String key = cacheKey(lat, lng);
		String cached = cache.get(key);
		if (cached != null) {
			return cached;
		}

		String locationName = "";
		try {
			NominatimResponse response = restClient.get()
					.uri(NOMINATIM_URL + "?lat={lat}&lon={lon}&format=jsonv2&zoom=14", lat, lng)
					.header("User-Agent", USER_AGENT)
					.retrieve()
					.body(NominatimResponse.class);
			if (response != null && response.address() != null) {
				Address a = response.address();
				String place = firstNonBlank(a.city(), a.town(), a.village(), a.suburb(), a.county());
				if (place != null && a.country() != null) {
					locationName = place + ", " + a.country();
				}
				else {
					locationName = place != null ? place : (a.country() != null ? a.country() : "");
				}
			}
		}
		catch (Exception e) {
			log.warn("Reverse geocode failed for ({}, {})", lat, lng, e);
		}

		cache.put(key, locationName);
		return locationName;
	}

	private static String firstNonBlank(String... values) {
		for (String v : values) {
			if (v != null && !v.isBlank()) return v;
		}
		return null;
	}

	private static String cacheKey(double lat, double lng) {
		return String.format(Locale.ROOT, "%.4f:%.4f", lat, lng);
	}
}
