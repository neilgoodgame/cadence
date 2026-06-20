package com.cadence.api.sharing.dto;

import com.cadence.api.users.dto.UserResponse;
import java.util.List;

public record ContextsResponse(UserResponse self, List<CoachedAthleteResponse> coaching, List<ShareResponse> coachedBy) {
}
