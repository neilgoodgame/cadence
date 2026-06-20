package com.cadence.api.uploads;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UploadStatusConverter extends LowercaseEnumConverter<UploadStatus> {

	public UploadStatusConverter() {
		super(UploadStatus.class);
	}
}
