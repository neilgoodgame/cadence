package com.cadence.api.athletes.dto;

import java.time.LocalDate;

/** One row in the full ledger for a field - GET /v1/athletes/{id}/threshold-history?field=...
 * (the history screen), most recent first. effectiveFrom is the qualifying activity's own date;
 * currentFrom is the date this row actually became the recorded current value - see
 * ThresholdHistory.getCurrentFrom()'s Javadoc for why those can differ. */
public record ThresholdHistoryEntryResponse(Object value, String sourceActivityId, LocalDate effectiveFrom, LocalDate currentFrom) {
}
