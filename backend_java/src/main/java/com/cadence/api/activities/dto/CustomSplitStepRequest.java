package com.cadence.api.activities.dto;

/** One step of a POST /v1/activities/{id}/splits or /v1/races/{id}/splits custom-splits request
 * - either {@code end_type="time"} (duration_s seconds) or {@code end_type="distance"}
 * (distance_km kilometres). Steps are consumed sequentially from the start of the recording, so
 * they can never overlap or be out of order - see ActivitySplitsService.customSplits. */
public record CustomSplitStepRequest(String endType, Integer durationS, Double distanceKm, String label) {}
