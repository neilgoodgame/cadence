package com.cadence.api.activities.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code parentId} is optional - omit for a top-level comment, or the id of an existing
 * top-level comment to reply to it. See {@code ActivityCommentService.create}'s Javadoc for the
 * single-level-threading rule this enforces. */
public record ActivityCommentCreateRequest(@NotBlank @Size(max = 4000) String text, String parentId) {
}
