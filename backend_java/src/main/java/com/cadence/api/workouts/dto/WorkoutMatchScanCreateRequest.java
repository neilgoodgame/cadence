package com.cadence.api.workouts.dto;

import java.util.List;

/** Optional request body for POST .../match-scans - the leaf step kinds to leave OUT of the
 * correlation for this one scan (not an athlete-wide preference). Omit the body entirely, or
 * pass {@code excludedStepKinds: null}, to get the default of {@code ["warmup", "cool"]}. */
public record WorkoutMatchScanCreateRequest(List<String> excludedStepKinds) {
}
