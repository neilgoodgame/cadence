package com.cadence.api.export.dto;

/** The top-level "profile" export section - the athlete's own live profile settings (age/
 * weight/ftp/criticalRunPower/thresholdPace/lthr/maxHr/restingHr), present only when the export
 * was requested with includeProfile=true (see ExportWriter.write). Unlike every other section,
 * this is account configuration, not portable training-history data - the reader (ImportReader.
 * importProfile) deliberately overwrites the importing athlete's own values with these, which is
 * only safe/wanted because the exporting athlete explicitly opted in. */
public record ProfileExportEntry(Integer age, Double weightKg, Integer ftp, Integer criticalRunPower,
		String thresholdPace, Integer lthr, Integer maxHr, Integer restingHr) {
}
