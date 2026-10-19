package com.cadence.api.activities.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** GET /v1/activities/{id}/splits - see ActivitySplitsService. Mirrors races.dto.SplitsResponse
 * without any goal/target reference or delta - a plain activity has nothing to compare against. */
public record ActivitySplitsResponse(boolean hasLaps, Halves halves, List<Split> splits) {

	public record Halves(int firstS, int secondS) {}

	public record Split(
			String label, double startKm, double endKm, int timeS,
			@JsonProperty("pace_s_per_km") Double paceSPerKm,
			@JsonProperty("gap_pace_s_per_km") Double gapPaceSPerKm,
			Integer avgHr, Integer avgPower,
			String sectionType, Integer gainM, Integer lossM, Double avgGradePct) {}
}
