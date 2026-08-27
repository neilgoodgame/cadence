package com.cadence.api.scheduling.dto;

import com.cadence.api.scheduling.ScheduledWorkoutStatus;
import com.cadence.api.scheduling.TimeOfDay;
import java.time.LocalDate;

/** {@code assignedByName}/{@code assignedByIsVirtual} are best-effort - see
 * {@code SchedulingMapper.toResponse}'s Javadoc for when they're populated vs. left null/false. */
public record ScheduledWorkoutResponse(
		String id, String workoutId, String athleteId, String assignedBy, String assignedByName,
		boolean assignedByIsVirtual, LocalDate date, TimeOfDay timeOfDay, ScheduledWorkoutStatus status,
		String activityId, String notes) {
}
