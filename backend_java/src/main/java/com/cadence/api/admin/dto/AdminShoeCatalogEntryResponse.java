package com.cadence.api.admin.dto;

import java.util.List;

public record AdminShoeCatalogEntryResponse(
		String id, String manufacturer, String model, List<ShoeCatalogVersionUsage> versions, String addedBy) {
}
