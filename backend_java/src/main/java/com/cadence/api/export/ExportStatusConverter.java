package com.cadence.api.export;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ExportStatusConverter extends LowercaseEnumConverter<ExportStatus> {

	public ExportStatusConverter() {
		super(ExportStatus.class);
	}
}
