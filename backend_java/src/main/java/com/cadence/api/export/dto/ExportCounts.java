package com.cadence.api.export.dto;

/** How many rows of each kind the export contains, within the same sport-filtered scope as the
 * file's own activities/races/workouts/scheduled_workouts sections - equipment stays full
 * regardless of the filter, matching those sections' own scope. Written once, upfront, from
 * cheap count queries (no row materialization) so a reader can see what the file contains
 * without having to fully parse it. */
public record ExportCounts(
		long activities, long races, long workouts, long scheduledWorkouts, long thresholdHistory, long bikes, long shoes,
		long components) {
}
