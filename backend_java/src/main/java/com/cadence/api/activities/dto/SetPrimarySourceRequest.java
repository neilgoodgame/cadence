package com.cadence.api.activities.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /v1/activities/{id}/primary-source request body (issue #503 phase 1). {@code field} is
 * always "power" today - phase 1 only supports power sources - but kept as a string, not a
 * fields-whitelist enum, for the same forward-compatibility reason {@code
 * ActivitySource.sensorKind} is. */
public record SetPrimarySourceRequest(@NotBlank String field, @NotBlank String sourceId) {
}
