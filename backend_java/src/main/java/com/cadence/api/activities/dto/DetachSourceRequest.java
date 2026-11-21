package com.cadence.api.activities.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /v1/activities/{id}/detach-source request body (issue #542). {@code preview} is boxed,
 * not primitive, same reasoning as {@code MergeActivitiesRequest} - omitted means false, the
 * REST default (the MCP tool defaults it true instead, for agent safety). */
public record DetachSourceRequest(@NotBlank String sourceId, Boolean preview) {

	public boolean previewOrDefault() {
		return preview != null && preview;
	}
}
