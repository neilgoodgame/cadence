package com.cadence.api.webhooks;

public record ActivityCreatedEvent(String activityId, String athleteId) {
}
