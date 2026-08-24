package com.cadence.api.scheduling.dto;

import com.cadence.api.scheduling.TimeOfDay;
import java.time.LocalDate;

public record ScheduledWorkoutUpdateRequest(LocalDate date, String activityId, TimeOfDay timeOfDay, String notes) {
}
