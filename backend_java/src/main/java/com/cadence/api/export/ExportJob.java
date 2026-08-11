package com.cadence.api.export;

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

/** One athlete has at most one export on record at a time - starting a new one replaces it (see ExportController). */
@Entity
@Table(name = "export_job")
public class ExportJob extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private ExportStatus status = ExportStatus.QUEUED;

	/** One of ExportWriter's fixed section names ("equipment"/"workouts"/"activities"/"races"/
	 * "scheduled_workouts") - null before processing starts. Lets a progress dialog show which
	 * kind of data is currently being worked on; see ExportWriter.write's Javadoc for why there's
	 * no finer-grained progress within a section. */
	@Column(name = "current_step")
	private String currentStep;

	/** Relative to the uploads media root, e.g. "usr_xxx/exports/exp_yyy.json.gz" - resolved against cadence.uploads.media-root, same as Upload.storedPath. */
	@Column(name = "file_path")
	private String filePath;

	@Column(name = "file_size_bytes")
	private Long fileSizeBytes;

	@Column(name = "error_message")
	private String errorMessage;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@PrePersist
	private void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "exp";
	}

	public User getAthlete() {
		return athlete;
	}

	public void setAthlete(User athlete) {
		this.athlete = athlete;
	}

	public ExportStatus getStatus() {
		return status;
	}

	public void setStatus(ExportStatus status) {
		this.status = status;
	}

	public String getCurrentStep() {
		return currentStep;
	}

	public void setCurrentStep(String currentStep) {
		this.currentStep = currentStep;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public Long getFileSizeBytes() {
		return fileSizeBytes;
	}

	public void setFileSizeBytes(Long fileSizeBytes) {
		this.fileSizeBytes = fileSizeBytes;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
	}
}
