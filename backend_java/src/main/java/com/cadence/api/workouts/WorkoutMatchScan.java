package com.cadence.api.workouts;

import com.cadence.api.common.id.PrefixedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A background scan of the workout's athlete's own unmatched, same-sport activities for likely
 * matches, ranked by Pearson correlation between each activity's actual power stream and the
 * workout's planned %FTP-vs-time curve - see {@link WorkoutMatchScanService}. Mirrors
 * {@code ExportJob}'s status lifecycle/progress-pair shape, since fetching a full per-second
 * Record stream per candidate is too slow to do synchronously in the request. Kept as history
 * (one row per scan run), like {@code ImportJob}, not replaced in place like {@code ExportJob}.
 */
@Entity
@Table(name = "workout_match_scan")
public class WorkoutMatchScan extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workout_id", nullable = false)
	private Workout workout;

	@Column(nullable = false)
	private WorkoutMatchScanStatus status = WorkoutMatchScanStatus.QUEUED;

	// Null until the duration pre-filter has run - mirrors ExportJob.totalItems's own
	// null-until-upfront-count-query window.
	@Column(name = "total_candidates")
	private Integer totalCandidates;

	@Column(name = "processed_candidates", nullable = false)
	private int processedCandidates;

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
		return "wms";
	}

	public Workout getWorkout() {
		return workout;
	}

	public void setWorkout(Workout workout) {
		this.workout = workout;
	}

	public WorkoutMatchScanStatus getStatus() {
		return status;
	}

	public void setStatus(WorkoutMatchScanStatus status) {
		this.status = status;
	}

	public Integer getTotalCandidates() {
		return totalCandidates;
	}

	public void setTotalCandidates(Integer totalCandidates) {
		this.totalCandidates = totalCandidates;
	}

	public int getProcessedCandidates() {
		return processedCandidates;
	}

	public void setProcessedCandidates(int processedCandidates) {
		this.processedCandidates = processedCandidates;
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
