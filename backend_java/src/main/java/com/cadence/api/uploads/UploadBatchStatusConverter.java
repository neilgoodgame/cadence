package com.cadence.api.uploads;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UploadBatchStatusConverter extends LowercaseEnumConverter<UploadBatchStatus> {

	public UploadBatchStatusConverter() {
		super(UploadBatchStatus.class);
	}
}
