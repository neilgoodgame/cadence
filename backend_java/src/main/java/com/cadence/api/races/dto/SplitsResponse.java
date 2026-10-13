package com.cadence.api.races.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** GET /v1/races/{id}/splits - see RaceSplitsService.
 *
 * Jackson's SNAKE_CASE naming strategy collapses consecutive capitals (no underscore between
 * adjacent uppercase letters), so a single-letter-unit field followed by another capitalised
 * word - "...SPerKm" - needs an explicit @JsonProperty or it serializes as "...sper_km" instead
 * of the contract's "..._s_per_km" (openapi.yaml). */
public record SplitsResponse(
		boolean hasLaps, String ref, @JsonProperty("ref_pace_s_per_km") Double refPaceSPerKm, Halves halves, List<Split> splits) {

	public record Halves(int firstS, int secondS) {}

	public record Split(
			String label, double startKm, double endKm, int timeS,
			@JsonProperty("pace_s_per_km") Double paceSPerKm, Integer avgHr,
			@JsonProperty("delta_vs_ref_s_per_km") Double deltaVsRefSPerKm) {}
}
