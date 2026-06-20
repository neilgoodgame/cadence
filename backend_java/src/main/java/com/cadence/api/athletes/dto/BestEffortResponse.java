package com.cadence.api.athletes.dto;

import java.time.LocalDate;

public record BestEffortResponse(String window, double value, String unit, LocalDate date, String activityId) {
}
