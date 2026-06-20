package com.cadence.api.activities;

import com.cadence.api.activities.dto.TagResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface TagMapper {

	TagResponse toResponse(Tag tag);
}
