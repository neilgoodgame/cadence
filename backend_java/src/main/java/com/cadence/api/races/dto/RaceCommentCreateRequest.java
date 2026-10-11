package com.cadence.api.races.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code week} pins the comment to a training-block week. {@code parentId} is optional -
 * omit for a top-level comment, or the id of an existing top-level comment to reply to it. See
 * {@code RaceCommentService.create}'s Javadoc for the single-level-threading rule this enforces. */
public record RaceCommentCreateRequest(@NotBlank @Size(max = 2000) String text, Integer week, String parentId) {
}
