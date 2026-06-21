package com.cadence.api.gear;

import com.cadence.api.gear.dto.ComponentResponse;
import com.cadence.api.gear.dto.ServiceRecordResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface GearMapper {

	@Mapping(target = "bikeId", source = "bike.id")
	ComponentResponse toResponse(Component component);

	@Mapping(target = "componentId", source = "component.id")
	ServiceRecordResponse toResponse(ServiceRecord serviceRecord);
}
