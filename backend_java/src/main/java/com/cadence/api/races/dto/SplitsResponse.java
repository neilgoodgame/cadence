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
		boolean hasLaps, String ref, @JsonProperty("ref_pace_s_per_km") Double refPaceSPerKm, Halves halves, List<Split> splits,
		// "target" (Race.targetPower) or "average" (the activity's own avgPower) - mirrors
		// ref/refPaceSPerKm's goal-vs-average split. Appended at the end (see the record-DTO
		// gotcha below) rather than placed next to ref/refPaceSPerKm.
		String refPower, @JsonProperty("ref_power_w") Integer refPowerW) {

	public record Halves(int firstS, int secondS) {}

	public record Split(
			String label, double startKm, double endKm, int timeS,
			@JsonProperty("pace_s_per_km") Double paceSPerKm, Integer avgHr,
			@JsonProperty("delta_vs_ref_s_per_km") Double deltaVsRefSPerKm,
			// Set only for mode=elevation - null for laps/1/5. Appended at the end rather than
			// placed near the other section-ish fields, so existing positional constructor
			// calls keep compiling (see backend_java/CLAUDE.md's record-DTO gotcha).
			String sectionType, Integer gainM, Integer lossM, Double avgGradePct,
			// Null if the activity has no power meter - same convention as avgHr.
			Integer avgPower,
			// Grade-adjusted pace (Minetti et al. 2002) - null when avgGradePct is null.
			@JsonProperty("gap_pace_s_per_km") Double gapPaceSPerKm,
			// avgPower vs refPowerW - preferred over deltaVsRefSPerKm/GAP by the frontend
			// whenever the activity has power data, since power already accounts for grade
			// mechanically. Null unless both avgPower and refPowerW are present.
			@JsonProperty("delta_power_w") Integer deltaPowerW) {}
}
