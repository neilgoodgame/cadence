package com.cadence.api.webhooks;

public record ScheduledWorkoutMatchedEvent(String scheduledWorkoutId, String athleteId) {
}
