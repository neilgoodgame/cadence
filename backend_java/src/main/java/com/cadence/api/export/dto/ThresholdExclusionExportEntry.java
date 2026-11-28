package com.cadence.api.export.dto;

/** One row of the top-level "threshold_exclusions" export section (issue #616): an activity the
 * athlete excluded from threshold detection for one field. {@code field} is the wire value, kept a
 * plain string so ImportReader can skip (rather than fail on) a value it doesn't recognise. The
 * exclusion's setBy isn't exported - it names a user on the exporting system - and an import
 * leaves it null. See ExportWriter.writeThresholdExclusions / ImportReader.importThresholdExclusions. */
public record ThresholdExclusionExportEntry(String field, String activityId) {
}
