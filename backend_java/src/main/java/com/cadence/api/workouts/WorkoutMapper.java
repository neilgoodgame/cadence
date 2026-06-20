package com.cadence.api.workouts;

import com.cadence.api.workouts.dto.WorkoutResponse;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface WorkoutMapper {

	WorkoutResponse toResponse(Workout workout);

	WorkoutStepDto toDto(WorkoutStep step);
}
