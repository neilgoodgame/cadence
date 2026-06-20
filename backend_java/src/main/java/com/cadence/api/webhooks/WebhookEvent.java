package com.cadence.api.webhooks;

import java.util.Set;

/** Event names are free strings on the wire (e.g. {@code activity.created}), validated against this fixed set rather than typed as an enum. */
public final class WebhookEvent {

	public static final String ACTIVITY_CREATED = "activity.created";
	public static final String SCHEDULED_WORKOUT_MATCHED = "scheduled_workout.matched";
	public static final String UPLOAD_BATCH_COMPLETED = "upload_batch.completed";

	public static final Set<String> ALL = Set.of(ACTIVITY_CREATED, SCHEDULED_WORKOUT_MATCHED, UPLOAD_BATCH_COMPLETED);

	private WebhookEvent() {
	}
}
