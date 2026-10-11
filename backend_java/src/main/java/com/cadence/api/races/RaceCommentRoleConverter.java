package com.cadence.api.races;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RaceCommentRoleConverter extends LowercaseEnumConverter<RaceCommentRole> {

	public RaceCommentRoleConverter() {
		super(RaceCommentRole.class);
	}
}
