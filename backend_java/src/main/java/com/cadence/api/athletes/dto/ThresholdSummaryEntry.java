package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.ThresholdHistoryKind;
import java.time.LocalDate;

/** One field's current state from GET /v1/athletes/{id}/thresholds - `value`/`previousValue` are
 * dual-typed (Integer for ftp/criticalRunPower, "M:SS" String for thresholdPace), matching
 * whichever field this is. `stale` is a cheap date comparison (source activity aged out of the
 * trailing window), never a recompute - see ThresholdHistoryService.isStale. `setByUserId` is
 * null for an auto-derived value (sourceActivityId is populated instead); for a manually-entered
 * value, the id of whoever set it - equal to this athlete's own id for a self-edit, a different
 * user's id if a coach set it on their behalf. `decay` (issue #485) is non-null exactly when
 * `kind == DECAYED` - see ThresholdDecayRecomputeService.decayObjectFor. */
public record ThresholdSummaryEntry(Object value, Object previousValue, String sourceActivityId,
		LocalDate effectiveFrom, boolean stale, String setByUserId, ThresholdHistoryKind kind, Decay decay) {

	/** `anchorValue`/`target` are dual-typed exactly like the outer record's `value`. */
	public record Decay(Object anchorValue, LocalDate anchorEffectiveFrom, LocalDate decayingSince, Object target) {
	}
}
