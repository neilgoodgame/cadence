package com.cadence.api.activities.dto;

import com.cadence.api.athletes.ThresholdField;

/** One ThresholdHistory row this activity is (or was) the source of - the activity page's
 * "currently/previously defines your FTP" indicator. `value` is dual-typed (Integer for
 * ftp/criticalRunPower, "M:SS" String for thresholdPace), matching `field`. `isCurrent` is false
 * once a later activity's effort has superseded this one for the same field. */
public record ActivityThresholdHistoryEntry(ThresholdField field, Object value, boolean isCurrent) {
}
