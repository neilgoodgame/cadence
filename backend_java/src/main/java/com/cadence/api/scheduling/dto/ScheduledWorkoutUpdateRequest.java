package com.cadence.api.scheduling.dto;

import java.time.LocalDate;

public record ScheduledWorkoutUpdateRequest(LocalDate date, String activityId) {
}
