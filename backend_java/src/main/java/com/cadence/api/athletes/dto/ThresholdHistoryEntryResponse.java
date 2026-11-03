package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.ThresholdHistoryKind;
import java.time.LocalDate;

/** One row in the full ledger for a field - GET /v1/athletes/{id}/threshold-history?field=...
 * (the history screen), most recent first. effectiveFrom is the qualifying activity's own date;
 * currentFrom is the date this row actually became the recorded current value - see
 * ThresholdHistory.getCurrentFrom()'s Javadoc for why those can differ. setByUserId is null for
 * an auto-derived row (sourceActivityId is populated instead); for a manually-entered row, the
 * id of whoever set it - equal to this athlete's own id for a self-edit, a different user's id
 * if a coach set it on their behalf. */
public record ThresholdHistoryEntryResponse(Object value, String sourceActivityId, LocalDate effectiveFrom,
		LocalDate currentFrom, String setByUserId, ThresholdHistoryKind kind) {
}
