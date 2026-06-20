package com.cadence.api.sharing.dto;

import com.cadence.api.sharing.ShareRole;
import java.time.Instant;

public record CoachedAthleteResponse(
		String relationshipId, String userId, String name, ShareRole role, double compliance, int tsb, Instant lastActivityAt) {
}
