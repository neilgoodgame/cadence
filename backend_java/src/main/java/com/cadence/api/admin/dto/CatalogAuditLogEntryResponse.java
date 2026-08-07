package com.cadence.api.admin.dto;

import com.cadence.api.admin.CatalogAuditAction;
import java.time.Instant;

public record CatalogAuditLogEntryResponse(
		String id, String description, CatalogAuditAction action, String by, Instant created) {
}
