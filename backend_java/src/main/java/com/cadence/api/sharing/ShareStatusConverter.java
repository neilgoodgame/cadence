package com.cadence.api.sharing;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ShareStatusConverter extends LowercaseEnumConverter<ShareStatus> {

	public ShareStatusConverter() {
		super(ShareStatus.class);
	}
}
