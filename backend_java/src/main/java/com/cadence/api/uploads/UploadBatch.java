package com.cadence.api.uploads;

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

@Entity
@Table(name = "upload_batch")
public class UploadBatch extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private String filename;

	@Column(name = "on_duplicate", nullable = false)
	private OnDuplicate onDuplicate = OnDuplicate.SKIP;

	@Column(nullable = false)
	private UploadBatchStatus status = UploadBatchStatus.PROCESSING;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "error_code", nullable = false)
	private String errorCode = "";

	@Column(name = "error_message", nullable = false)
	private String errorMessage = "";

	@PrePersist
	private void onCreate() {
		if (receivedAt == null) {
			receivedAt = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "bat";
	}

	public User getAthlete() {
		return athlete;
	}

	public void setAthlete(User athlete) {
		this.athlete = athlete;
	}

	public String getFilename() {
		return filename;
	}

	public void setFilename(String filename) {
		this.filename = filename;
	}

	public OnDuplicate getOnDuplicate() {
		return onDuplicate;
	}

	public void setOnDuplicate(OnDuplicate onDuplicate) {
		this.onDuplicate = onDuplicate;
	}

	public UploadBatchStatus getStatus() {
		return status;
	}

	public void setStatus(UploadBatchStatus status) {
		this.status = status;
	}

	public Instant getReceivedAt() {
		return receivedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public void setErrorCode(String errorCode) {
		this.errorCode = errorCode;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}
}
