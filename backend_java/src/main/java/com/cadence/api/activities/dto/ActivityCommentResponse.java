package com.cadence.api.activities.dto;

import java.time.Instant;

public record ActivityCommentResponse(
		String id, String activityId, String authorId, String authorName, String authorRole, String parentId,
		String text, Instant created) {
}
