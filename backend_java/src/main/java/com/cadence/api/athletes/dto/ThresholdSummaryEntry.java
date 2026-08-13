package com.cadence.api.athletes.dto;

import java.time.LocalDate;

/** One field's current state from GET /v1/athletes/{id}/thresholds - `value`/`previousValue` are
 * dual-typed (Integer for ftp/criticalRunPower, "M:SS" String for thresholdPace), matching
 * whichever field this is. `stale` is a cheap date comparison (source activity aged out of the
 * trailing window), never a recompute - see ThresholdHistoryService.isStale. */
public record ThresholdSummaryEntry(
		Object value, Object previousValue, String sourceActivityId, LocalDate effectiveFrom, boolean stale) {
}
