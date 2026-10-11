package com.cadence.api.races.dto;

import java.time.Instant;

public record RaceCommentResponse(
		String id, String raceId, String authorId, String authorName, String role, String text, Integer week,
		String parentId, Instant createdAt) {
}
