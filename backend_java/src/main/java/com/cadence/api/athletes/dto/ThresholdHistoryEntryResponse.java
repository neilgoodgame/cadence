package com.cadence.api.athletes.dto;

import java.time.LocalDate;

/** One row in the full ledger for a field - GET /v1/athletes/{id}/threshold-history?field=...
 * (the history screen), most recent first. */
public record ThresholdHistoryEntryResponse(Object value, String sourceActivityId, LocalDate effectiveFrom) {
}
