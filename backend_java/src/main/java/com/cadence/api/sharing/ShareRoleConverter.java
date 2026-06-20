package com.cadence.api.sharing;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ShareRoleConverter extends LowercaseEnumConverter<ShareRole> {

	public ShareRoleConverter() {
		super(ShareRole.class);
	}
}
