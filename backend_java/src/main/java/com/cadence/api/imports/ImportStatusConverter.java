package com.cadence.api.imports;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ImportStatusConverter extends LowercaseEnumConverter<ImportStatus> {

	public ImportStatusConverter() {
		super(ImportStatus.class);
	}
}
