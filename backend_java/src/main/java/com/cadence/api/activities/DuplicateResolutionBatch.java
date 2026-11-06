package com.cadence.api.activities;

import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Issue #498's {@code resolveDuplicatesBatch(..., dryRun=false)} - one call that actually
 * applied an action to many overlap pairs at once, recorded so {@code undoBatch} can reverse the
 * whole thing later. Never created for a dry run (nothing happened, nothing to undo). Always
 * scoped to one athlete, same as every other duplicate-resolution path - a batch never spans
 * athletes.
 */
@Entity
@Table(name = "duplicate_resolution_batch")
public class DuplicateResolutionBatch extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	/** Only {@code MARK}/{@code MERGE} are ever stored - validated at the service layer, same as
	 * every other field this narrow relative to the full {@link OverlapHandling} enum. */
	@Column(nullable = false)
	private OverlapHandling action;

	/** The acting principal - the athlete itself for a self-triggered batch, a different User
	 * for a coach acting on their behalf. Informational only; nothing currently reads it back. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_id")
	private User createdBy;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant undoneAt;

	@PrePersist
	private void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "drb";
	}

	public User getAthlete() {
		return athlete;
	}

	public void setAthlete(User athlete) {
		this.athlete = athlete;
	}

	public OverlapHandling getAction() {
		return action;
	}

	public void setAction(OverlapHandling action) {
		this.action = action;
	}

	public User getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(User createdBy) {
		this.createdBy = createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUndoneAt() {
		return undoneAt;
	}

	public void setUndoneAt(Instant undoneAt) {
		this.undoneAt = undoneAt;
	}
}
