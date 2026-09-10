package com.cadence.api.activities;

import com.cadence.api.activities.dto.LapResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface LapMapper {

	@Mapping(target = "workoutStepId", source = "workoutStep.id")
	@Mapping(target = "stepKind", source = "workoutStep.kind")
	@Mapping(target = "stepTargetType", source = "workoutStep.targetType")
	@Mapping(target = "stepTargetLow", source = "workoutStep.targetLow")
	@Mapping(target = "stepTargetHigh", source = "workoutStep.targetHigh")
	@Mapping(target = "stepPowerUnit", source = "workoutStep.powerUnit")
	LapResponse toResponse(Lap lap);
}
