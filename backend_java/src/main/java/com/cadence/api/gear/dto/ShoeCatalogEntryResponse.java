package com.cadence.api.gear.dto;

public record ShoeCatalogEntryResponse(
		String shoeModelVersionId, String manufacturer, String model, String version, String displayName) {
}
