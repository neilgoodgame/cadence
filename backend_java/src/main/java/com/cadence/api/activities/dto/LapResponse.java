package com.cadence.api.activities.dto;

public record LapResponse(int index, int duration, double distanceKm, Integer avgHr, Integer avgPower) {
}
