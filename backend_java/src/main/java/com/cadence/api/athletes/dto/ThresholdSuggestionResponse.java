package com.cadence.api.athletes.dto;

import java.io.Serializable;
import java.time.LocalDate;

/** One pending Threshold suggestion - shape varies by `kind`. See GET
 * /v1/athletes/{id}/threshold-suggestions. Serializable: held in the in-memory
 * ThresholdSuggestionService cache (see that class's CacheManager). */
public record ThresholdSuggestionResponse(
		String id,
		String field,
		String kind,
		Object current,
		Object proposed,
		Double deltaPct,
		String activityId,
		LocalDate activityDate,
		ImpliedFrom impliedFrom,
		LocalDate expiryDate,
		Integer daysLeft,
		RaceInfo race) implements Serializable {

	public record ImpliedFrom(String window, double value) implements Serializable {
	}

	public record RaceInfo(String id, String name, LocalDate date) implements Serializable {
	}

	/** days_left drifts daily even within the cache's own TTL - recomputed from expiryDate at
	 * read time (ThresholdSuggestionService.listSuggestions) rather than caching a number that
	 * goes stale within the day. */
	public ThresholdSuggestionResponse withDaysLeft(int newDaysLeft) {
		return new ThresholdSuggestionResponse(
				id, field, kind, current, proposed, deltaPct, activityId, activityDate, impliedFrom, expiryDate, newDaysLeft, race);
	}
}
