package com.cadence.api.activities.dto;

import com.cadence.api.activities.ActivityStreamFlagService.Kind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST /v1/activities/{id}/streams/patch request body (issue #540, phase 6). {@code segments}
 * is either a list of {@code [startT, endT]} pairs or the literal string {@code "flagged"} -
 * parsed by {@code ActivityStreamPatchService#parseSegments}, since Jackson has no clean way to
 * bind a String-or-array union directly onto a typed field. {@code kind}/{@code reference} are
 * only meaningful (and required) when {@code segments="flagged"}; ignored otherwise. */
public record StreamPatchRequest(@NotBlank String field, @NotBlank String fromSource, @NotNull Object segments,
		Kind kind, String reference, Boolean preview) {

	public boolean previewOrDefault() {
		return preview != null && preview;
	}
}
