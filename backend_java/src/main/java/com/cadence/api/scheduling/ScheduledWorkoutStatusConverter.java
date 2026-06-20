package com.cadence.api.scheduling;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ScheduledWorkoutStatusConverter extends LowercaseEnumConverter<ScheduledWorkoutStatus> {

	public ScheduledWorkoutStatusConverter() {
		super(ScheduledWorkoutStatus.class);
	}
}
