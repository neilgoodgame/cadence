package com.cadence.api.scheduling.dto;

import com.cadence.api.scheduling.ScheduledWorkoutStatus;
import com.cadence.api.scheduling.TimeOfDay;
import java.time.LocalDate;

public record ScheduledWorkoutResponse(
		String id, String workoutId, String athleteId, String assignedBy, LocalDate date,
		TimeOfDay timeOfDay, ScheduledWorkoutStatus status, String activityId) {
}
