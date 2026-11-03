package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.ThresholdHistoryService.RerateDiff;
import java.time.LocalDate;

/** One activity's before/after intensity+tss from a (real or previewed) re-rate - part of
 * ThresholdEditResultResponse.wouldRerate (issue #493). */
public record RerateDiffResponse(
		String activityId, LocalDate date, Double oldIntensity, Double newIntensity, Integer oldTss, Integer newTss) {

	public static RerateDiffResponse from(RerateDiff diff) {
		return new RerateDiffResponse(
				diff.activityId(), diff.date(), diff.oldIntensity(), diff.newIntensity(), diff.oldTss(), diff.newTss());
	}
}
