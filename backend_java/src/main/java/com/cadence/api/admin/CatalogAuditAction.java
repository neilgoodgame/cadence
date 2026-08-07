package com.cadence.api.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CatalogAuditAction {
	ADDED, REMOVED;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static CatalogAuditAction fromWireValue(String value) {
		return CatalogAuditAction.valueOf(value.toUpperCase());
	}
}
