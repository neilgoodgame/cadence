package com.cadence.api.garmin.dto;

import com.cadence.api.garmin.GarminWorkoutOp;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code tcx} is only included for pending operations (that is what the bridge applies).
 * {@code garminWorkoutId} and {@code garminScheduleId} are resolved from earlier results where
 * known, so the bridge can target the right Garmin objects.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GarminWorkoutOpResponse(String id, String kind, String status, String workoutId,
		String scheduledWorkoutId, LocalDate date, String name, String tcx, Long garminWorkoutId,
		Long garminScheduleId, String error, Instant created, Instant completedAt) {

	@JsonProperty("object")
	public String object() {
		return "garmin_workout_op";
	}

	public static GarminWorkoutOpResponse from(GarminWorkoutOp op, Long garminWorkoutId, Long garminScheduleId) {
		boolean pending = op.getStatus() == com.cadence.api.garmin.GarminWorkoutOpStatus.PENDING;
		return new GarminWorkoutOpResponse(op.getId(), op.getKind().name().toLowerCase(),
				op.getStatus().name().toLowerCase(), op.getWorkoutId(), op.getScheduledWorkoutId(), op.getDate(),
				op.getName(), pending ? op.getTcx() : null, garminWorkoutId, garminScheduleId, op.getError(),
				op.getCreated(), op.getCompletedAt());
	}
}
