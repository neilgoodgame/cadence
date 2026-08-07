package com.cadence.api.admin.dto;

import com.cadence.api.sharing.ShareRole;
import java.time.Instant;

public record AdminRelationshipResponse(String id, String coachName, String athleteName, ShareRole role, Instant granted) {
}
