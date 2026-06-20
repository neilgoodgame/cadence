package com.cadence.api.activities;

import com.cadence.api.activities.dto.LapResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface LapMapper {

	LapResponse toResponse(Lap lap);
}
