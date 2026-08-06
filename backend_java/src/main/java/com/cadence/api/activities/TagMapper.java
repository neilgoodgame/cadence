package com.cadence.api.activities;

import com.cadence.api.activities.dto.TagResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TagMapper {

	// Usage count isn't a Tag property - it's an athlete-scoped aggregate over ActivityTag,
	// computed separately by TagService.listTagsWithCounts and not relevant to a single
	// attach/detach response, so it's left at the record's default (0) here.
	@Mapping(target = "count", ignore = true)
	TagResponse toResponse(Tag tag);
}
