package com.cadence.api.athletes.dto;

import com.cadence.api.activities.BestEffortKind;
import java.time.LocalDate;
import java.util.List;

/** Issue #582: `period` is null for a date-range query (and vice versa - the dates are null
 * for a period query), so callers can always tell which mode produced the list. */
public record BestEffortListResponse(BestEffortKind kind, String period, List<BestEffortResponse> data,
		LocalDate startDate, LocalDate endDate) {
}
