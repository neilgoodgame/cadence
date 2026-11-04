package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.users.dto.UserResponse;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;
import java.util.Map;

/** {@code allOf: [Athlete, {zones_recomputed, threshold_edit_results}]} in the contract - a flat
 * merge, not a nested object. thresholdEditResults (issue #493) has one entry per threshold
 * field actually present in the request, keyed by its wire name (ftp/critical_run_power/
 * threshold_pace) - empty when none was. */
public record AthleteUpdateResponse(
		@JsonUnwrapped UserResponse athlete, List<String> zonesRecomputed, Map<String, ThresholdEditResultResponse> thresholdEditResults) {

	public static Map<String, ThresholdEditResultResponse> thresholdEditResultsWireMap(
			Map<ThresholdField, com.cadence.api.athletes.ThresholdHistoryService.ManualEditResult> results) {
		Map<String, ThresholdEditResultResponse> wire = new java.util.LinkedHashMap<>();
		results.forEach((field, result) -> wire.put(field.wireValue(), ThresholdEditResultResponse.from(result)));
		return wire;
	}
}
