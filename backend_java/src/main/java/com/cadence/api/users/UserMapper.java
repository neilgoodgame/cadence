package com.cadence.api.users;

import com.cadence.api.users.dto.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

	@Mapping(target = "isCoach", source = "coach")
	@Mapping(target = "isAdmin", source = "admin")
	UserResponse toResponse(User user);
}
