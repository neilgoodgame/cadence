package com.cadence.api.activities.dto;

import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** POST /v1/activities/{id}/merge request body - the path {id} is the target.
 * {@code hideSource}/{@code preview} are boxed, not primitive - like {@code AthleteUpdateRequest}'s
 * own optional booleans, since Jackson 3's record deserialization has no field to fall back to
 * false for an omitted JSON property on a primitive constructor parameter; it 400s instead. */
public record MergeActivitiesRequest(@NotBlank String sourceId, @NotEmpty List<String> fields,
		@NotNull NullPolicy nullPolicy, Integer offsetSeconds, Boolean hideSource, Boolean preview) {

	public boolean hideSourceOrDefault() {
		return hideSource != null && hideSource;
	}

	public boolean previewOrDefault() {
		return preview != null && preview;
	}
}
