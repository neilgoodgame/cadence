package com.cadence.api.common.id;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

/**
 * Base for every entity addressed through the API by a Stripe-style id
 * (e.g. {@code usr_...}, {@code act_...}). Internal-only join/log tables
 * (Lap, Record, WorkoutStep, ActivityTag, WebhookDelivery, ...) do not
 * extend this - they use an ordinary surrogate or composite key instead.
 */
@MappedSuperclass
public abstract class PrefixedIdEntity {

	@Id
	@Column(length = 40, nullable = false, updatable = false)
	private String id;

	protected abstract String idPrefix();

	@PrePersist
	protected void assignId() {
		if (id == null) {
			id = PrefixedId.generate(idPrefix());
		}
	}

	public String getId() {
		return id;
	}
}
