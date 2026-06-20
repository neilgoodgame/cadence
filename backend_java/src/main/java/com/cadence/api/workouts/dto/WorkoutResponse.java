package com.cadence.api.workouts.dto;

import com.cadence.api.common.domain.Sport;

public record WorkoutResponse(String id, String name, Sport sport, String type, int duration, int tss) {
}
