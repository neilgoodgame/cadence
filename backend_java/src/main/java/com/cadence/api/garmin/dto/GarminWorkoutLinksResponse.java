package com.cadence.api.garmin.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Which workouts and calendar entries are on Garmin Connect, and how much is still waiting. */
public record GarminWorkoutLinksResponse(List<WorkoutLink> workouts, List<ScheduleLink> schedules, long pending,
		long failed) {

	public record WorkoutLink(String workoutId, long garminWorkoutId, Instant pushedAt) {
	}

	public record ScheduleLink(String scheduledWorkoutId, long garminScheduleId, LocalDate scheduledFor) {
	}
}
