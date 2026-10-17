package com.cadence.api.export.dto;

import com.cadence.api.activities.BestEffortKind;
import java.time.LocalDate;

/** One row of the top-level "best_efforts" export section - the athlete's own already-computed,
 * already-capped (per best_effort_top_n) best-effort rows, carried over verbatim (re-linked to
 * the newly-created activity id) so an export/import round trip needs no post-import recompute
 * to show a populated Best Efforts / Top Efforts screen (see ExportWriter.writeBestEfforts /
 * ImportReader.importBestEfforts). */
public record BestEffortExportEntry(BestEffortKind kind, String window, double value, String unit, LocalDate date,
		String activityId) {
}
