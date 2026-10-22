package com.cadence.api.garmin;

import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "garmin_sync_run")
public class GarminSyncRun extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private User owner;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(nullable = false)
	private int found;

	@Column(name = "already_synced", nullable = false)
	private int alreadySynced;

	@Column(nullable = false)
	private int requested;

	@Column(nullable = false)
	private int failed;

	@Column(nullable = false)
	private boolean truncated;

	private String error;

	@Column(name = "batch_id")
	private String batchId;

	@Override
	protected String idPrefix() {
		return "gsr";
	}

	public User getOwner() {
		return owner;
	}

	public void setOwner(User owner) {
		this.owner = owner;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public void setFinishedAt(Instant finishedAt) {
		this.finishedAt = finishedAt;
	}

	public int getFound() {
		return found;
	}

	public void setFound(int found) {
		this.found = found;
	}

	public int getAlreadySynced() {
		return alreadySynced;
	}

	public void setAlreadySynced(int alreadySynced) {
		this.alreadySynced = alreadySynced;
	}

	public int getRequested() {
		return requested;
	}

	public void setRequested(int requested) {
		this.requested = requested;
	}

	public int getFailed() {
		return failed;
	}

	public void setFailed(int failed) {
		this.failed = failed;
	}

	public boolean isTruncated() {
		return truncated;
	}

	public void setTruncated(boolean truncated) {
		this.truncated = truncated;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String batchId) {
		this.batchId = batchId;
	}
}
