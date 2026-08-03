package com.cadence.api.imports.dto;

public record ImportCounts(
		int activitiesImported, int racesImported, int workoutsImported, int scheduledWorkoutsImported,
		int bikesImported, int shoesImported, int componentsImported, int itemsSkipped) {
}
