package com.cadence.api.imports.dto;

public record ImportCounts(
		int activitiesImported, int coursesImported, int racesImported, int raceCommentsImported, int workoutsImported,
		int scheduledWorkoutsImported, int thresholdHistoryImported, int bestEffortsImported, int bikesImported,
		int shoesImported, int componentsImported, int itemsSkipped) {
}
