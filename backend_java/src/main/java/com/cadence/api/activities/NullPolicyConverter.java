package com.cadence.api.activities;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class NullPolicyConverter extends LowercaseEnumConverter<ActivityMergeApplyService.NullPolicy> {

	public NullPolicyConverter() {
		super(ActivityMergeApplyService.NullPolicy.class);
	}
}
