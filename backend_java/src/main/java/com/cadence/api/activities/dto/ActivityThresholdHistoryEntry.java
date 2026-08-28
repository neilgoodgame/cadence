package com.cadence.api.activities.dto;

import com.cadence.api.athletes.ThresholdField;

/** One ThresholdHistory row associated with this activity, in one of two ways distinguished by
 * `sourceActivityId` - the activity page's "currently/previously defines your FTP" indicator.
 * `sourceActivityId` equal to this activity's own id means this activity's own effort produced
 * the row; a *different* id means this activity's own ingest/recompute pass is what revealed an
 * earlier, dormant effort as the new current value once its window rival aged out (see
 * ThresholdHistory.getCurrentFrom()'s Javadoc). `value` is dual-typed (Integer for
 * ftp/criticalRunPower, "M:SS" String for thresholdPace), matching `field`. `isCurrent` is false
 * once a later activity's effort has superseded this one for the same field. */
public record ActivityThresholdHistoryEntry(ThresholdField field, Object value, boolean isCurrent, String sourceActivityId) {
}
