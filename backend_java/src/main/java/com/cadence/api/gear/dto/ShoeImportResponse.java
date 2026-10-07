package com.cadence.api.gear.dto;

public record ShoeImportResponse(
		int shoesCreated,
		int catalogModelsCreated,
		int catalogVersionsCreated,
		int skippedNoCatalogMatch,
		int skippedAlreadyInGear) {
}
