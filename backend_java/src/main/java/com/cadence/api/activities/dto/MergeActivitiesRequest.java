package com.cadence.api.activities.dto;

import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.activities.MergeCopyMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** POST /v1/activities/{id}/merge request body - the path {id} is the target.
 * {@code hideSource}/{@code preview} are boxed, not primitive - like {@code AthleteUpdateRequest}'s
 * own optional booleans, since Jackson 3's record deserialization has no field to fall back to
 * false for an omitted JSON property on a primitive constructor parameter; it 400s instead.
 * {@code copyMode} is likewise boxed/optional (issue #503 phase 1) - omitted means {@code
 * overwrite}, today's only behavior, so every existing caller is unaffected. {@code sourceKind}
 * (issue #503 phase 3) is only meaningful with {@code copyMode=attach} and {@code
 * fields=["heartrate"]} or {@code ["cadence"]} - ignored for {@code ["power"]}, which still
 * classifies by device instead. */
public record MergeActivitiesRequest(@NotBlank String sourceId, @NotEmpty List<String> fields,
		@NotNull NullPolicy nullPolicy, Integer offsetSeconds, Boolean hideSource, Boolean preview,
		MergeCopyMode copyMode, String sourceKind) {

	public boolean hideSourceOrDefault() {
		return hideSource != null && hideSource;
	}

	public boolean previewOrDefault() {
		return preview != null && preview;
	}

	public MergeCopyMode copyModeOrDefault() {
		return copyMode != null ? copyMode : MergeCopyMode.OVERWRITE;
	}
}
