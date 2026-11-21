package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.ThresholdHistoryService.QualificationDiff;
import java.time.LocalDate;

/** One ledger slot where a rebuild would produce a different winner than what's currently
 * stored - part of ThresholdEditResultResponse.wouldRequalify (issue #494). */
public record QualificationDiffResponse(
		LocalDate currentFrom, String oldActivityId, String newActivityId, Object oldValue, Object newValue) {

	public static QualificationDiffResponse from(QualificationDiff diff) {
		return new QualificationDiffResponse(
				diff.currentFrom(), diff.oldActivityId(), diff.newActivityId(), diff.oldValue(), diff.newValue());
	}
}
