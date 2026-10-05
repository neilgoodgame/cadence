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
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

	// Which leaf step kinds counted toward the correlation for this scan - chosen once, at
	// creation, by whoever triggered it (see WorkoutMatchScanController), not an athlete-wide
	// preference. Same JSON-list mapping as Workout.tags.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "excluded_step_kinds", nullable = false)
	private List<String> excludedStepKinds = new ArrayList<>();

	// Whether the candidate pre-filter compares each activity's actual movingTime or
	// distanceKm against the workout's planned total - chosen once, at creation, same as
	// excludedStepKinds above. TIME (the default) is exact for a time-ended plan; DISTANCE
	// exists because a distance-ended plan's `duration` column is itself just a pace estimate
	// (see WorkoutMatchScanService.totalPlannedDistanceMeters), so for e.g. a trail long run
	// whose real pacing varies with terrain, comparing distance (the one quantity the plan
	// actually fixes) instead of duration avoids rejecting a genuine match purely on normal
	// pacing variance. The athlete picks whichever matches how the workout's steps are
	// actually structured - a mixed workout (some time-ended, some distance-ended steps) is
	// valid either way, just less precise.
	@Column(name = "duration_basis", nullable = false)
	private MatchScanDurationBasis durationBasis = MatchScanDurationBasis.TIME;

	// Whether the power stream is smoothed (see WorkoutMatchScanService.SMOOTHING_WINDOW_SECONDS)
	// before correlating against the workout's plan - chosen once, at creation, same as the
	// fields above. Off by default: it changes the score itself (filtering real
	// second-to-second noise so a session whose segment averages genuinely match the plan isn't
	// penalized for terrain/stride variability within each segment), not just additional
	// information, so an athlete opts in deliberately rather than every scan's numbers shifting
	// silently.
	@Column(name = "smooth_power", nullable = false)
	private boolean smoothPower;

	// Which scoring method the correlation pass uses - chosen once, at creation, same as the
	// fields above. POWER (the default) correlates the activity's real power stream against the
	// plan's %FTP-vs-time/distance curve - LAPS ignores power entirely and instead checks
	// whether the activity's own real device laps structurally match the plan's steps (see
	// WorkoutMatchScanService.correlateLaps): "did the athlete run the prescribed structure",
	// not "did they hit the prescribed numbers". Requires an exact lap-count match against the
	// workout's steps; durationBasis/smoothPower don't apply in this mode (no power stream
	// involved).
	@Column(name = "correlation_basis", nullable = false)
	private MatchScanCorrelationBasis correlationBasis = MatchScanCorrelationBasis.POWER;

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

	public List<String> getExcludedStepKinds() {
		return excludedStepKinds;
	}

	public void setExcludedStepKinds(List<String> excludedStepKinds) {
		this.excludedStepKinds = excludedStepKinds;
	}

	public MatchScanDurationBasis getDurationBasis() {
		return durationBasis;
	}

	public void setDurationBasis(MatchScanDurationBasis durationBasis) {
		this.durationBasis = durationBasis;
	}

	public boolean isSmoothPower() {
		return smoothPower;
	}

	public void setSmoothPower(boolean smoothPower) {
		this.smoothPower = smoothPower;
	}

	public MatchScanCorrelationBasis getCorrelationBasis() {
		return correlationBasis;
	}

	public void setCorrelationBasis(MatchScanCorrelationBasis correlationBasis) {
		this.correlationBasis = correlationBasis;
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
