package com.cadence.api.export.dto;

import com.cadence.api.athletes.ThresholdField;
import java.time.LocalDate;

/** One row of the top-level "threshold_history" export section - the full ledger, independent of
 * any one activity (see ExportWriter.writeThresholdHistory / ImportReader.importThresholdHistory).
 * Dual-typed like the ThresholdHistory entity it mirrors: valueNumeric for ftp/criticalRunPower,
 * valuePace for thresholdPace - only one is ever populated, matching `field`. */
public record ThresholdHistoryExportEntry(ThresholdField field, Integer valueNumeric, String valuePace,
		String sourceActivityId, LocalDate effectiveFrom, LocalDate currentFrom) {
}
