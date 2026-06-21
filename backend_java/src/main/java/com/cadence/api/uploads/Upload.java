package com.cadence.api.uploads;

import com.cadence.api.activities.Activity;
import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.gear.Shoe;
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
@Table(name = "upload")
public class Upload extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "batch_id")
	private UploadBatch batch;

	@Column(nullable = false)
	private String filename;

	@Column(name = "file_hash", nullable = false)
	private String fileHash;

	@Column(name = "stored_path", nullable = false)
	private String storedPath = "";

	@Column(nullable = false)
	private UploadStatus status = UploadStatus.QUEUED;

	private Double progress;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "activity_id")
	private Activity activity;

	@Column(name = "error_code", nullable = false)
	private String errorCode = "";

	@Column(name = "error_message", nullable = false)
	private String errorMessage = "";

	@Column(name = "weight_before_kg")
	private Double weightBeforeKg;

	@Column(name = "weight_after_kg")
	private Double weightAfterKg;

	@Column(name = "fluids_ml")
	private Integer fluidsMl;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "shoe_id")
	private Shoe shoe;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@PrePersist
	private void onCreate() {
		if (receivedAt == null) {
			receivedAt = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "upl";
	}

	public User getAthlete() {
		return athlete;
	}

	public void setAthlete(User athlete) {
		this.athlete = athlete;
	}

	public UploadBatch getBatch() {
		return batch;
	}

	public void setBatch(UploadBatch batch) {
		this.batch = batch;
	}

	public String getFilename() {
		return filename;
	}

	public void setFilename(String filename) {
		this.filename = filename;
	}

	public String getFileHash() {
		return fileHash;
	}

	public void setFileHash(String fileHash) {
		this.fileHash = fileHash;
	}

	public String getStoredPath() {
		return storedPath;
	}

	public void setStoredPath(String storedPath) {
		this.storedPath = storedPath;
	}

	public UploadStatus getStatus() {
		return status;
	}

	public void setStatus(UploadStatus status) {
		this.status = status;
	}

	public Double getProgress() {
		return progress;
	}

	public void setProgress(Double progress) {
		this.progress = progress;
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
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

	public Double getWeightBeforeKg() {
		return weightBeforeKg;
	}

	public void setWeightBeforeKg(Double weightBeforeKg) {
		this.weightBeforeKg = weightBeforeKg;
	}

	public Double getWeightAfterKg() {
		return weightAfterKg;
	}

	public void setWeightAfterKg(Double weightAfterKg) {
		this.weightAfterKg = weightAfterKg;
	}

	public Integer getFluidsMl() {
		return fluidsMl;
	}

	public void setFluidsMl(Integer fluidsMl) {
		this.fluidsMl = fluidsMl;
	}

	public Shoe getShoe() {
		return shoe;
	}

	public void setShoe(Shoe shoe) {
		this.shoe = shoe;
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
}
