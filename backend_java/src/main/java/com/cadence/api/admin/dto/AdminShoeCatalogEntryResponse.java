package com.cadence.api.admin.dto;

import java.util.List;

public record AdminShoeCatalogEntryResponse(
		String id, String manufacturer, String model, List<String> versions, String addedBy) {
}
