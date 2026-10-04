package com.cadence.api.workouts.dto;

import java.util.List;

/** Optional request body for POST .../match-scans - the leaf step kinds to leave OUT of the
 * correlation for this one scan (not an athlete-wide preference). Omit the body entirely, or
 * pass {@code excludedStepKinds: null}, to get the default of {@code ["warmup", "cool"]}.
 *
 * <p>{@code durationBasis} ("time", the default, or "distance") picks which of the workout's
 * planned totals the candidate pre-filter compares each activity against - see
 * {@code WorkoutMatchScan.getDurationBasis()}'s own Javadoc for why "distance" exists at all.
 * Kept as a raw String (not the {@code MatchScanDurationBasis} enum directly) so an invalid
 * value fails with the same friendly, field-specific 400 {@code excludedStepKinds} gets, rather
 * than a generic body-deserialization error. */
public record WorkoutMatchScanCreateRequest(List<String> excludedStepKinds, String durationBasis) {
}
