package com.cadence.api.admin;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CatalogAuditActionConverter extends LowercaseEnumConverter<CatalogAuditAction> {

	public CatalogAuditActionConverter() {
		super(CatalogAuditAction.class);
	}
}
