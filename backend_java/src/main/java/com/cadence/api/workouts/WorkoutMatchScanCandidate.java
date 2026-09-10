package com.cadence.api.workouts;

import com.cadence.api.activities.Activity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One candidate activity a {@link WorkoutMatchScan} evaluated, however low its correlation -
 * not just the top N - so the full ranked list stays inspectable.
 */
@Entity
@Table(name = "workout_match_scan_candidate")
public class WorkoutMatchScanCandidate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "scan_id", nullable = false)
	private WorkoutMatchScan scan;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	@Column(nullable = false)
	private double correlation;

	@Column(name = "duration_diff_seconds", nullable = false)
	private int durationDiffSeconds;

	@Column(nullable = false)
	private double coverage;

	// Informational only (regression-slope-derived), never used for ranking - see
	// WorkoutMatchScanService.correlateActivity.
	@Column(name = "implied_ftp")
	private Integer impliedFtp;

	public Long getId() {
		return id;
	}

	public WorkoutMatchScan getScan() {
		return scan;
	}

	public void setScan(WorkoutMatchScan scan) {
		this.scan = scan;
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
	}

	public double getCorrelation() {
		return correlation;
	}

	public void setCorrelation(double correlation) {
		this.correlation = correlation;
	}

	public int getDurationDiffSeconds() {
		return durationDiffSeconds;
	}

	public void setDurationDiffSeconds(int durationDiffSeconds) {
		this.durationDiffSeconds = durationDiffSeconds;
	}

	public double getCoverage() {
		return coverage;
	}

	public void setCoverage(double coverage) {
		this.coverage = coverage;
	}

	public Integer getImpliedFtp() {
		return impliedFtp;
	}

	public void setImpliedFtp(Integer impliedFtp) {
		this.impliedFtp = impliedFtp;
	}
}
