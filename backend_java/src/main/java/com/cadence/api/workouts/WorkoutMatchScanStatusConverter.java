package com.cadence.api.workouts;

import com.cadence.api.common.jpa.LowercaseEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class WorkoutMatchScanStatusConverter extends LowercaseEnumConverter<WorkoutMatchScanStatus> {

	public WorkoutMatchScanStatusConverter() {
		super(WorkoutMatchScanStatus.class);
	}
}
