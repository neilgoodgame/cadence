package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.ThresholdHistoryService.ManualEditResult;
import java.util.List;

/** One threshold field's outcome from a manual edit (issue #493) - part of
 * AthleteUpdateResponse.thresholdEditResults. isCorrectionOnly is true when the edit was written
 * as a historical correction only (a later row already existed, or it was already outside
 * thresholdWindowDays as of today) rather than becoming the live value - see
 * ThresholdHistoryService.recordManualValue's Javadoc. reratedCount is populated on a real
 * (non-preview) run; wouldRerate/wouldRequalify only on a preview - see
 * ThresholdHistoryService.QualificationDiff's Javadoc for wouldRequalify (issue #494). */
public record ThresholdEditResultResponse(boolean changed, boolean becameCurrent, boolean isCorrectionOnly,
		int reratedCount, List<RerateDiffResponse> wouldRerate, List<QualificationDiffResponse> wouldRequalify) {

	public static ThresholdEditResultResponse from(ManualEditResult result) {
		return new ThresholdEditResultResponse(result.changed(), result.becameCurrent(), result.isCorrectionOnly(),
				result.reratedCount(), result.wouldRerate().stream().map(RerateDiffResponse::from).toList(),
				result.wouldRequalify().stream().map(QualificationDiffResponse::from).toList());
	}
}
